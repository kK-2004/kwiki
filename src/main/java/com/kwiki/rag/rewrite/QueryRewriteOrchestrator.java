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
 * 编排改写的选取与执行。截止时间由 LLM 适配器负责；
 * 编排器保证空白、无效或失败的改写绝不中止
 * 安全检索——归一化后的原始查询始终是最后兜底——并
 * 在保持顺序的同时对等价子查询去重。
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
