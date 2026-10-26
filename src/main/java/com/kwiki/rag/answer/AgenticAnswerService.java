package com.kwiki.rag.answer;

import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.rag.orchestration.RouteInput;
import com.kwiki.rag.routing.PriorRoute;
import com.kwiki.security.CurrentUser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgenticAnswerService {
    private static final Logger log = LoggerFactory.getLogger(AgenticAnswerService.class);

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
        return answer(user, query, sessionId, clientMessageId, agentId, kbIds, pageIds, null);
    }

    public Flux<ChatStreamEvent> answer(CurrentUser user, String query,
                                        String sessionId, String clientMessageId,
                                        String agentId, java.util.Set<Long> kbIds,
                                        java.util.Set<Long> pageIds, ClarificationReply reply) {
        return answer(user, query, sessionId, clientMessageId, agentId, kbIds, pageIds, reply, null);
    }

    /**
     * quote 为已按权限解析的划线引用：用户消息仍存原话、引用单独入库；
     * 送给工作流的有效问题带上引用，意图识别、改写与工具规划照常进行。
     */
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query,
                                        String sessionId, String clientMessageId,
                                        String agentId, java.util.Set<Long> kbIds,
                                        java.util.Set<Long> pageIds, ClarificationReply reply, ChatQuote quote) {
        ChatSessionService.RunHandle run = null;
        if (sessions != null) {
            try {
                run = quote == null
                        ? sessions.begin(user, sessionId, clientMessageId, agentId, query)
                        : sessions.begin(user, sessionId, clientMessageId, agentId, query, quote);
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

        // 路由状态：上一轮路由；若这是对反问的回答，校验后合成有效问题并收窄范围，且本次禁止再反问。
        PriorRoute prior = durableRun == null ? null : sessions.priorRoute(durableRun.runId());
        String effectiveQuery = quote == null ? query : quote.compose(query);
        java.util.Set<Long> effectiveKbIds = kbIds == null ? java.util.Set.of() : kbIds;
        java.util.Set<Long> effectivePageIds = pageIds == null ? java.util.Set.of() : pageIds;
        boolean resumed = false;
        if (reply != null && durableRun != null) {
            PendingClarification pending = PendingClarification.parse(
                    sessions.pendingClarification(durableRun.runId()));
            Optional<PendingClarification.Resolved> resolved =
                    pending == null ? Optional.empty() : pending.resolve(reply);
            if (resolved.isPresent()) {
                effectiveQuery = resolved.get().effectiveQuery();
                effectiveKbIds = resolved.get().kbScope(effectiveKbIds);
                effectivePageIds = resolved.get().pageScope(effectivePageIds);
                resumed = true;
            }
        }
        final String workflowQuery = effectiveQuery;
        final java.util.Set<Long> workflowKbIds = effectiveKbIds;
        final java.util.Set<Long> workflowPageIds = effectivePageIds;
        final RouteInput routeInput = new RouteInput(prior, resumed);

        AtomicReference<PriorRoute> finalRoute = new AtomicReference<>();
        AtomicReference<String> pendingJson = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean successful = new java.util.concurrent.atomic.AtomicBoolean(true);
        Flux<ChatStreamEvent> workflowEvents = (durableRun == null
                ? workflow.answer(user, workflowQuery, history)
                : workflow.answerInSession(user, workflowQuery, history, workflowKbIds, workflowPageIds, routeInput))
                .doOnNext(event -> {
                    if ("error".equals(event.type())) successful.set(false);
                    if ("token".equals(event.type())) answer.append(String.valueOf(event.payloadMap().getOrDefault("text", "")));
                    if ("clarification".equals(event.type())) {
                        Map<String, Object> payload = event.payloadMap();
                        // 反问问题作为助手消息入库，历史里可见；待答记录随 run 一起保存。
                        answer.append(String.valueOf(payload.getOrDefault("question", "")));
                        pendingJson.set(PendingClarification.from(payload, workflowQuery).toJson());
                    }
                    if ("done".equals(event.type()) && event.payloadMap().get("route") instanceof Map<?, ?> route) {
                        finalRoute.set(PriorRoute.fromMap(route));
                    }
                })
                .doOnComplete(() -> {
                    if (durableRun == null) return;
                    sessions.finish(user, durableRun, query, answer.toString(), successful.get(), successful.get() ? null : "answer_failed");
                    safely("recordRoute", () -> sessions.recordRoute(durableRun.runId(), finalRoute.get()));
                    if (pendingJson.get() != null) {
                        safely("savePendingClarification",
                                () -> sessions.savePendingClarification(durableRun.runId(), pendingJson.get()));
                    }
                })
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

    /** 路由状态的持久化是尽力而为：失败只记日志，不影响已经发出的实时流。 */
    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("chat route state persistence failed: {}", what, failure);
        }
    }
}
