package com.kwiki.rag.routing;

import java.util.List;

/**
 * 经过校验的路由结果：意图、检索需求、改写模式、有界子查询，
 * 以及完整的溯源信息（来源、规则版本、命中的规则、回退原因）。
 * 构造上不可变；消费方绝不会直接收到 LLM 的输出。
 */
public record RetrievalPlan(
        Intent intent,
        boolean needsRetrieval,
        RewriteMode rewriteMode,
        List<String> subqueries,
        RouteSource source,
        String ruleVersion,
        List<String> matchedRuleIds,
        String fallbackReason,
        double confidence) {

    public RetrievalPlan {
        subqueries = subqueries == null ? List.of() : List.copyOf(subqueries);
        matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
    }

    public static RetrievalPlan rule(Intent intent, String ruleVersion, List<String> ruleIds) {
        return new RetrievalPlan(intent, true, defaultRewrite(intent), List.of(),
                RouteSource.RULE, ruleVersion, ruleIds, null, 1.0);
    }

    public static RetrievalPlan llm(Intent intent, boolean needsRetrieval, RewriteMode mode,
                                    List<String> subqueries, double confidence) {
        return new RetrievalPlan(intent, needsRetrieval, mode, subqueries,
                RouteSource.LLM, null, List.of(), null, confidence);
    }

    public static RetrievalPlan fallback(String reason) {
        return new RetrievalPlan(Intent.KNOWLEDGE_QA, true, RewriteMode.NONE, List.of(),
                RouteSource.FALLBACK, null, List.of(), reason, 0.0);
    }

    private static RewriteMode defaultRewrite(Intent intent) {
        return intent == Intent.ANALYTICAL ? RewriteMode.DECOMPOSITION : RewriteMode.NONE;
    }
}
