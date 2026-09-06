package com.kwiki.rag.rewrite;

import com.kwiki.rag.routing.QueryNormalizer;
import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.routing.RewriteMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates rewrite selection and execution. Deadlines live in the LLM adapter;
 * the orchestrator guarantees that blank, invalid, or failed rewrites never abort
 * safe retrieval — the normalized original query is always the last resort — and
 * that equivalent subqueries are deduplicated while order is preserved.
 */
@Component
public class QueryRewriteOrchestrator {

    private final RewriteDecisionService decisions;
    private final ConversationalRewriter conversational;
    private final ExpansionRewriter expansion;
    private final DecompositionRewriter decomposition;
    private final MeterRegistry metrics;

    public QueryRewriteOrchestrator(RewriteDecisionService decisions,
                                    ConversationalRewriter conversational,
                                    ExpansionRewriter expansion,
                                    DecompositionRewriter decomposition,
                                    MeterRegistry metrics) {
        this.decisions = decisions;
        this.conversational = conversational;
        this.expansion = expansion;
        this.decomposition = decomposition;
        this.metrics = metrics;
    }

    public RewriteResult rewrite(String rawQuery, RetrievalPlan plan,
                                 List<ChatTurn> recentHistory) {
        String normalized = QueryNormalizer.normalize(rawQuery);
        if (QueryNormalizer.isBlank(normalized)) {
            return RewriteResult.fallback(normalized, "blank-query");
        }
        RewriteMode mode = decisions.decide(plan, normalized, recentHistory);
        RewriteResult result = switch (mode) {
            case CONVERSATIONAL -> safe(() -> conversational.rewrite(normalized, recentHistory),
                    normalized);
            case EXPANSION -> safe(() -> expansion.rewrite(normalized), normalized);
            case DECOMPOSITION -> safe(() -> decomposition.rewrite(normalized), normalized);
            case NONE -> RewriteResult.none(normalized);
        };
        RewriteResult deduplicated = dedupe(result);
        metrics.counter("kwiki_rewrite_total", "mode", deduplicated.mode().name()).increment();
        if (deduplicated.fallbackReason() != null) {
            metrics.counter("kwiki_rewrite_fallback_total", "reason",
                    deduplicated.fallbackReason()).increment();
        }
        return deduplicated;
    }

    private RewriteResult safe(java.util.function.Supplier<RewriteResult> action,
                               String normalized) {
        try {
            RewriteResult result = action.get();
            if (result.effectiveQueries().isEmpty()
                    || result.effectiveQueries().stream().anyMatch(String::isBlank)) {
                return RewriteResult.fallback(normalized, "rewrite-blank-output");
            }
            return result;
        } catch (Exception e) {
            return RewriteResult.fallback(normalized, "rewrite-error");
        }
    }

    private static RewriteResult dedupe(RewriteResult result) {
        Set<String> unique = new LinkedHashSet<>(result.effectiveQueries());
        if (unique.size() == result.effectiveQueries().size()) {
            return result;
        }
        return new RewriteResult(result.original(), result.mode(), List.copyOf(unique),
                result.fallbackReason() == null ? "deduplicated" : result.fallbackReason());
    }
}
