package com.kwiki.rag.answer;

import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.security.CurrentUser;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AgenticAnswerService {
    private final AgenticWorkflowPort workflow;
    private final ChatSessionService sessions;

    public AgenticAnswerService(AgenticWorkflowPort workflow) {
        this(workflow, null);
    }

    @Autowired
    public AgenticAnswerService(AgenticWorkflowPort workflow, ObjectProvider<ChatSessionService> sessions) {
        this.workflow = workflow;
        this.sessions = sessions == null ? null : sessions.getIfAvailable();
    }

    public Flux<ChatStreamEvent> answer(CurrentUser user, String query) {
        return workflow.answer(user, query);
    }

    /** Adds the session envelope without changing the legacy query-only workflow port. */
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query,
                                        String sessionId, String clientMessageId,
                                        String agentId) {
        ChatSessionService.RunHandle run = null;
        if (sessions != null) {
            try {
                run = sessions.begin(user, sessionId, clientMessageId, agentId, query);
            } catch (IllegalStateException unavailable) {
                if (!"database is unavailable".equals(unavailable.getMessage())) throw unavailable;
                // The query-only stream remains available while the optional DB is offline.
            }
        }
        final ChatSessionService.RunHandle durableRun = run;
        String requestId = durableRun == null ? UUID.randomUUID().toString() : durableRun.requestId();
        AtomicLong sequence = new AtomicLong();
        Map<String, Object> session = Map.of(
                "sessionId", durableRun == null ? (sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId) : durableRun.sessionUuid(),
                "clientMessageId", durableRun == null ? (clientMessageId == null ? "" : clientMessageId) : durableRun.clientMessageId(),
                "agentId", agentId == null ? "default" : agentId);
        if (durableRun != null && durableRun.existing()) {
            return Flux.just(
                    ChatStreamEvent.of("session", sequence.incrementAndGet(), requestId, session),
                    ChatStreamEvent.of("done", sequence.incrementAndGet(), requestId,
                            Map.of("outcome", "duplicate", "message", "该消息已处理")));
        }
        StringBuilder answer = new StringBuilder();
        java.util.List<com.kwiki.rag.rewrite.ChatTurn> history = durableRun == null || durableRun.existing()
                ? java.util.List.of() : sessions.historyBefore(durableRun.runId());
        java.util.concurrent.atomic.AtomicBoolean successful = new java.util.concurrent.atomic.AtomicBoolean(true);
        Flux<ChatStreamEvent> workflowEvents = (durableRun == null ? workflow.answer(user, query, history) : workflow.answerInSession(user, query, history))
                .doOnNext(event -> {
                    if ("error".equals(event.type())) successful.set(false);
                    if ("token".equals(event.type())) answer.append(String.valueOf(event.payloadMap().getOrDefault("text", "")));
                })
                .doOnComplete(() -> { if (durableRun != null) sessions.finish(user, durableRun, query, answer.toString(), successful.get(), successful.get() ? null : "answer_failed"); })
                .doOnError(error -> { if (durableRun != null) sessions.finish(user, durableRun, query, answer.toString(), false, error.getClass().getSimpleName()); });
        return Flux.concat(
                Flux.just(ChatStreamEvent.of("session", sequence.incrementAndGet(), requestId, session)),
                workflowEvents.map(event -> ChatStreamEvent.of(
                        event.type(), sequence.incrementAndGet(), requestId, event.payloadMap())));
    }
}
