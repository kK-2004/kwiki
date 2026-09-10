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
    private final ChatRunEventStore runEvents;

    public AgenticAnswerService(AgenticWorkflowPort workflow) {
        this(workflow, null, null);
    }

    @Autowired
    public AgenticAnswerService(AgenticWorkflowPort workflow,
                                ObjectProvider<ChatSessionService> sessions,
                                ObjectProvider<ChatRunEventStore> runEvents) {
        this.workflow = workflow;
        this.sessions = sessions == null ? null : sessions.getIfAvailable();
        this.runEvents = runEvents == null ? null : runEvents.getIfAvailable();
    }

    public Flux<ChatStreamEvent> answer(CurrentUser user, String query) {
        return workflow.answer(user, query);
    }

    /** 在不改变原有「仅查询」工作流端口的前提下，增加会话信封。 */
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query,
                                        String sessionId, String clientMessageId,
                                        String agentId) {
        return answer(user, query, sessionId, clientMessageId, agentId, java.util.Set.of(), java.util.Set.of());
    }

    public Flux<ChatStreamEvent> answer(CurrentUser user, String query,
                                        String sessionId, String clientMessageId,
                                        String agentId, java.util.Set<Long> kbIds,
                                        java.util.Set<Long> pageIds) {
        ChatSessionService.RunHandle run = null;
        if (sessions != null) {
            try {
                run = sessions.begin(user, sessionId, clientMessageId, agentId, query);
            } catch (IllegalStateException unavailable) {
                if (!"database is unavailable".equals(unavailable.getMessage())) throw unavailable;
                // 当可选的数据库离线时，「仅查询」流仍然可用。
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
        Flux<ChatStreamEvent> workflowEvents = (durableRun == null ? workflow.answer(user, query, history) : workflow.answerInSession(user, query, history, kbIds, pageIds))
                .doOnNext(event -> {
                    if ("error".equals(event.type())) successful.set(false);
                    if ("token".equals(event.type())) answer.append(String.valueOf(event.payloadMap().getOrDefault("text", "")));
                })
                .doOnComplete(() -> { if (durableRun != null) sessions.finish(user, durableRun, query, answer.toString(), successful.get(), successful.get() ? null : "answer_failed"); })
                .doOnError(error -> { if (durableRun != null) sessions.finish(user, durableRun, query, answer.toString(), false, error.getClass().getSimpleName()); });
        Long sessionRowId = durableRun == null ? null : durableRun.sessionId();
        return Flux.concat(
                Flux.just(ChatStreamEvent.of("session", sequence.incrementAndGet(), requestId, session)),
                workflowEvents.map(event -> ChatStreamEvent.of(
                        event.type(), sequence.incrementAndGet(), requestId, event.payloadMap()))
                        // 持久化重新编号后的线缆事件以便回放；
                        // 存储失败绝不打断实时流。
                        .doOnNext(event -> {
                            if (runEvents != null) {
                                runEvents.append(requestId, sessionRowId, event);
                            }
                        }));
    }
}
