package com.kwiki.rag.routing;

import java.util.List;

/**
 * 经过校验的路由结果：意图、检索需求、改写模式、有界子查询，
 * 以及完整的溯源信息（来源、规则版本、命中的规则、回退原因）；
 * 可选地携带一次反问请求。构造上不可变；消费方绝不会直接收到 LLM 的输出。
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
        double confidence,
        ClarifyRequest clarify) {

    public RetrievalPlan {
        subqueries = subqueries == null ? List.of() : List.copyOf(subqueries);
        matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
    }

    /** 不带反问的 9 参构造，保持既有调用方不变。 */
    public RetrievalPlan(Intent intent, boolean needsRetrieval, RewriteMode rewriteMode,
                         List<String> subqueries, RouteSource source, String ruleVersion,
                         List<String> matchedRuleIds, String fallbackReason, double confidence) {
        this(intent, needsRetrieval, rewriteMode, subqueries, source, ruleVersion,
                matchedRuleIds, fallbackReason, confidence, null);
    }

    public static RetrievalPlan rule(Intent intent, String ruleVersion, List<String> ruleIds) {
        return new RetrievalPlan(intent, intent != Intent.DIRECT_ANSWER, defaultRewrite(intent), List.of(),
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

    /** QA planner 在检索后修正出的路由；confidence 取 1.0（由代码门槛背书，不是模型自评）。 */
    public static RetrievalPlan planner(Intent intent, boolean needsRetrieval) {
        return new RetrievalPlan(intent, needsRetrieval, RewriteMode.NONE, List.of(),
                RouteSource.PLANNER, null, List.of(), "planner-reroute", 1.0);
    }

    /** 规则直接触发的反问（例如无上下文的指代）。 */
    public static RetrievalPlan ruleClarify(String ruleId, ClarifyRequest clarify) {
        return new RetrievalPlan(Intent.KNOWLEDGE_QA, true, RewriteMode.NONE, List.of(),
                RouteSource.RULE, KeywordRuleSet.VERSION, List.of(ruleId), null, 1.0, clarify);
    }

    public RetrievalPlan withClarify(ClarifyRequest request) {
        return new RetrievalPlan(intent, needsRetrieval, rewriteMode, subqueries, source,
                ruleVersion, matchedRuleIds, fallbackReason, confidence, request);
    }

    public RetrievalPlan withoutClarify() {
        return withClarify(null);
    }

    public boolean needsClarification() {
        return clarify != null;
    }

    private static RewriteMode defaultRewrite(Intent intent) {
        return intent == Intent.ANALYTICAL ? RewriteMode.DECOMPOSITION : RewriteMode.NONE;
    }
}
