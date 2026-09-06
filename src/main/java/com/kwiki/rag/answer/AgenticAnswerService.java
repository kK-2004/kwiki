package com.kwiki.rag.answer;

import com.kwiki.rag.retrieval.HybridRetrievalOrchestrator;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.rewrite.QueryRewriteOrchestrator;
import com.kwiki.rag.rewrite.RewriteResult;
import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agentic answer orchestration streamed as SSE events: route → rewrite →
 * retrieve → (no-evidence short circuit) → evidence-bounded token stream →
 * citations → done. Exactly one terminal event per stream; errors terminate
 * sanitized; client cancellation propagates to the provider stream while audit
 * metadata (chat turn + trace) is still persisted.
 */
@org.springframework.stereotype.Service
public class AgenticAnswerService {

    private final RuleFirstRouter router;
    private final QueryRewriteOrchestrator rewrites;
    private final HybridRetrievalOrchestrator retrieval;
    private final EvidenceAssembler evidenceAssembler;
    private final AnswerLlmPort answerLlm;
    private final AuthorizationScopeResolver scopes;
    private final ChatPersistenceService persistence;
    private final MeterRegistry metrics;

    public AgenticAnswerService(RuleFirstRouter router,
                                QueryRewriteOrchestrator rewrites,
                                HybridRetrievalOrchestrator retrieval,
                                EvidenceAssembler evidenceAssembler,
                                AnswerLlmPort answerLlm,
                                AuthorizationScopeResolver scopes,
                                ChatPersistenceService persistence,
                                MeterRegistry metrics) {
        this.router = router;
        this.rewrites = rewrites;
        this.retrieval = retrieval;
        this.evidenceAssembler = evidenceAssembler;
        this.answerLlm = answerLlm;
        this.scopes = scopes;
        this.persistence = persistence;
        this.metrics = metrics;
    }

    public Flux<ChatStreamEvent> answer(CurrentUser user, String rawQuery) {
        String requestId = MDC.get("traceId") == null
                ? UUID.randomUUID().toString()
                : MDC.get("traceId");
        AtomicLong sequence = new AtomicLong();
        java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> tokenSubscription =
                new java.util.concurrent.atomic.AtomicReference<>();
        Sinks.Many<ChatStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder answerText = new StringBuilder();
        Map<String, Object> trace = new LinkedHashMap<>();

        sink.tryEmitNext(next(sequence, requestId, "route", Map.of()));
        try {
            RetrievalPlan plan = router.route(rawQuery);
            trace.put("routeSource", plan.source().name());
            trace.put("intent", plan.intent().name());
            sink.tryEmitNext(next(sequence, requestId, "route", Map.of(
                    "intent", plan.intent().name(),
                    "source", plan.source().name(),
                    "rewriteMode", plan.rewriteMode().name())));

            AuthorizationScope scope = scopes.resolve(user);
            RewriteResult rewrite = rewrites.rewrite(rawQuery, plan, List.of());
            trace.put("rewriteMode", rewrite.mode().name());
            sink.tryEmitNext(next(sequence, requestId, "rewrite", Map.of(
                    "mode", rewrite.mode().name(),
                    "effectiveQueries", rewrite.effectiveQueries())));

            HybridRetrievalOrchestrator.RetrievalOutcome outcome =
                    retrieval.retrieve(scope, rewrite, 8, 24000);
            sink.tryEmitNext(next(sequence, requestId, "retrieve", Map.of(
                    "parentCount", outcome.parents().size(),
                    "degradations", outcome.degradations())));

            List<ParentEvidence> evidence =
                    evidenceAssembler.assemble(outcome.parents(), 24000);
            if (evidence.isEmpty()) {
                trace.put("outcome", "no-evidence");
                sink.tryEmitNext(next(sequence, requestId, "done", Map.of(
                        "noEvidence", true,
                        "message", "未在当前可访问知识中找到依据")));
                persistence.persistTurn(UUID.randomUUID().toString(), user.id(), rawQuery,
                        null, trace);
                sink.tryEmitComplete();
                return sink.asFlux();
            }

            List<Map<String, Object>> citationPayload = citationPayload(evidence);
            String prompt = buildPrompt(evidence, rewrite.original());
            Flux<String> tokens = answerLlm.streamAnswer(prompt);

            tokenSubscription.set(tokens.subscribe(
                    token -> {
                        answerText.append(token);
                        sink.tryEmitNext(next(sequence, requestId, "token",
                                Map.of("text", token)));
                    },
                    error -> {
                        metrics.counter("kwiki_answer_total", "outcome", "provider-error")
                                .increment();
                        trace.put("outcome", "provider-error");
                        persistence.persistTurn(UUID.randomUUID().toString(), user.id(),
                                rawQuery, answerText.toString(), trace);
                        sink.tryEmitNext(next(sequence, requestId, "error",
                                Map.of("error", "answer-provider-failed")));
                        sink.tryEmitComplete();
                    },
                    () -> {
                        sink.tryEmitNext(next(sequence, requestId, "citations",
                                Map.of("citations", citationPayload)));
                        trace.put("outcome", "completed");
                        trace.put("citationCount", citationPayload.size());
                        persistence.persistTurn(UUID.randomUUID().toString(), user.id(),
                                rawQuery, answerText.toString(), trace);
                        metrics.counter("kwiki_answer_total", "outcome", "completed")
                                .increment();
                        sink.tryEmitNext(next(sequence, requestId, "done",
                                Map.of("citationCount", citationPayload.size())));
                        sink.tryEmitComplete();
                    }));

        } catch (HybridRetrievalOrchestrator.StaleScopeException stale) {
            sink.tryEmitNext(next(sequence, requestId, "error",
                    Map.of("error", "authorization-changed")));
            sink.tryEmitComplete();
        } catch (Exception failure) {
            metrics.counter("kwiki_answer_total", "outcome", "error").increment();
            sink.tryEmitNext(next(sequence, requestId, "error",
                    Map.of("error", sanitize(failure))));
            sink.tryEmitComplete();
        }
        return sink.asFlux()
                .doOnCancel(() -> {
                    // client disconnect must cancel the downstream provider stream
                    reactor.core.Disposable subscription = tokenSubscription.get();
                    if (subscription != null && !subscription.isDisposed()) {
                        subscription.dispose();
                    }
                });
    }

    private static ChatStreamEvent next(AtomicLong sequence, String requestId, String type,
                                        Map<String, Object> payload) {
        return ChatStreamEvent.of(type, sequence.incrementAndGet(), requestId, payload);
    }

    private static String sanitize(Exception failure) {
        if (failure instanceof com.kwiki.rag.retrieval.ConcurrentRecallService.RetrievalBranchException) {
            return "retrieval-failed";
        }
        return "internal-error";
    }

    private static List<Map<String, Object>> citationPayload(List<ParentEvidence> evidence) {
        List<Map<String, Object>> citations = new ArrayList<>();
        for (ParentEvidence parent : evidence) {
            for (com.kwiki.rag.retrieval.ChunkHit child : parent.matchedChildren()) {
                Map<String, Object> citation = new LinkedHashMap<>();
                citation.put("childChunkKey", child.chunkKey());
                citation.put("parentChunkKey", parent.parentChunkKey());
                citation.put("resourceType", parent.resourceType());
                citation.put("resourceId", parent.resourceId());
                citation.put("revisionId", parent.revisionId());
                citation.put("headingPath", child.headingPath());
                citation.put("charStart", child.charStart());
                citation.put("charEnd", child.charEnd());
                citation.put("excerpt", excerpt(child.content()));
                citations.add(citation);
            }
        }
        return citations;
    }

    private static String excerpt(String content) {
        return content.length() <= 120 ? content : content.substring(0, 120);
    }

    private static String buildPrompt(List<ParentEvidence> evidence, String query) {
        StringBuilder prompt = new StringBuilder("Question: ").append(query)
                .append("\n\nEvidence parent chunks:\n");
        for (ParentEvidence parent : evidence) {
            prompt.append("[P").append(parent.firstHitOrder()).append(" ")
                    .append(parent.headingPath()).append("]\n")
                    .append(parent.body()).append("\n\n");
        }
        prompt.append("Answer only from the evidence above; cite as [P0]/[P1] where used.");
        return prompt.toString();
    }
}
