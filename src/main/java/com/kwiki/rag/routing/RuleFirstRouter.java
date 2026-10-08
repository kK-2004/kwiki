package com.kwiki.rag.routing;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 规则优先的意图路由：命中单条终止性规则即可完全绕过
 * LLM；无命中、冲突以及复合查询则咨询（受 schema 约束的）
 * 路由器 LLM；每一种 LLM 失败情形都会降级为带作用域的 KNOWLEDGE_QA，
 * 并记录回退原因。
 *
 * <p>带上下文的路由：依赖上文的指代追问在有历史时升级到 LLM（无历史则反问）；
 * 规则得出的「是否需要检索」与上一轮不一致时同样升级到 LLM，LLM 不可用则保留规则结果。
 * 反问续跑的 run 不再产生任何反问。</p>
 */
@Component
public class RuleFirstRouter {

    static final Pattern ANAPHORA = Pattern.compile("^(它|这个|那个|上面|刚才)(呢|是什么|怎么用|怎么样)[？?]?$");
    static final String ANAPHORA_QUESTION = "请说明你指的是哪个页面、产品或主题。";
    static final String LOW_CONFIDENCE_QUESTION = "我不太确定你想了解什么，可以补充一些细节吗？";

    private final KeywordRuleSet rules;
    private final RouterLlmPort llm;
    private final boolean llmEnabled;
    private final MeterRegistry metrics;
    private final double clarifyThreshold;

    public RuleFirstRouter(KeywordRuleSet rules,
                           ObjectProvider<RouterLlmPort> llm,
                           boolean llmEnabled,
                           MeterRegistry metrics) {
        this(rules, llm, llmEnabled, metrics, 0.5);
    }

    @Autowired
    public RuleFirstRouter(KeywordRuleSet rules,
                           ObjectProvider<RouterLlmPort> llm,
                           @Value("${kwiki.routing.llm-enabled:false}") boolean llmEnabled,
                           MeterRegistry metrics,
                           @Value("${kwiki.routing.clarify-confidence-threshold:0.5}") double clarifyThreshold) {
        this.rules = rules;
        this.llm = llm.getIfAvailable();
        this.llmEnabled = llmEnabled;
        this.metrics = metrics;
        this.clarifyThreshold = clarifyThreshold;
    }

    public RetrievalPlan route(String rawQuery) {
        return route(rawQuery, RouteContext.EMPTY);
    }

    public RetrievalPlan route(String rawQuery, RouteContext context) {
        long start = System.nanoTime();
        RetrievalPlan plan = routeInternal(rawQuery, context == null ? RouteContext.EMPTY : context);
        io.micrometer.core.instrument.Timer.builder("kwiki_route_latency")
                .description("intent routing latency")
                .register(metrics)
                .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        return counted(plan);
    }

    private RetrievalPlan routeInternal(String rawQuery, RouteContext context) {
        String normalized = QueryNormalizer.normalize(rawQuery);
        if (ANAPHORA.matcher(normalized).matches()) {
            return routeAnaphora(normalized, context);
        }
        return switch (rules.match(normalized)) {
            case KeywordRuleSet.MatchResult.Resolved resolved ->
                    resolveAgainstPrior(normalized, resolved, context);
            case KeywordRuleSet.MatchResult.NeedsLlm needsLlm ->
                    routeWithLlm(normalized, needsLlm.reason(), context, null);
        };
    }

    private RetrievalPlan routeAnaphora(String normalized, RouteContext context) {
        if (context.clarificationResumed()) {
            return counted(RetrievalPlan.fallback("anaphora-after-clarification"));
        }
        if (context.recentUserQueries().isEmpty()) {
            return counted(RetrievalPlan.ruleClarify("anaphora-no-context",
                    new ClarifyRequest(ANAPHORA_QUESTION, List.of(), true, "router")));
        }
        metrics.counter("kwiki_route_conflict_total", "reason", "anaphora-with-context").increment();
        return routeWithLlm(normalized, "anaphora-with-context", context, null);
    }

    private RetrievalPlan resolveAgainstPrior(String normalized,
                                              KeywordRuleSet.MatchResult.Resolved resolved,
                                              RouteContext context) {
        RetrievalPlan rulePlan = RetrievalPlan.rule(resolved.intent(), rules.version(), resolved.ruleIds());
        PriorRoute prior = context.prior();
        if (prior == null || context.clarificationResumed()
                || prior.needsRetrieval() == rulePlan.needsRetrieval()) {
            return counted(rulePlan);
        }
        metrics.counter("kwiki_route_conflict_total", "reason", "retrieval-need-changed").increment();
        return routeWithLlm(normalized, "retrieval-need-changed", context, rulePlan);
    }

    /**
     * @param ruleResult 非空表示这是规则已定论、因冲突才升级的情形：LLM 关闭或失败时保留该规则结果；
     *                   为空则回退到带作用域的 KNOWLEDGE_QA。
     */
    private RetrievalPlan routeWithLlm(String normalized, String reason, RouteContext context,
                                       RetrievalPlan ruleResult) {
        if (!llmEnabled || llm == null) {
            return counted(ruleResult != null ? ruleResult : RetrievalPlan.fallback("llm-disabled:" + reason));
        }
        try {
            Optional<Map<String, Object>> decision = llm.askRouter(normalized, reason, context);
            if (decision.isEmpty()) {
                return counted(ruleResult != null ? ruleResult : RetrievalPlan.fallback("llm-unavailable"));
            }
            return counted(applyClarifyPolicy(RouterDecisionValidator.validate(decision.get()), context));
        } catch (IllegalArgumentException invalid) {
            return counted(ruleResult != null ? ruleResult : RetrievalPlan.fallback("llm-invalid-output"));
        } catch (Exception failure) {
            return counted(ruleResult != null ? ruleResult : RetrievalPlan.fallback("llm-error"));
        }
    }

    private RetrievalPlan applyClarifyPolicy(RetrievalPlan plan, RouteContext context) {
        if (context.clarificationResumed()) {
            return plan.withoutClarify();
        }
        if (plan.needsClarification()) {
            return plan;
        }
        // 置信度缺失时校验器给 0.0；只有显式给出且低于阈值才反问。
        if (plan.confidence() > 0.0 && plan.confidence() < clarifyThreshold) {
            return plan.withClarify(new ClarifyRequest(LOW_CONFIDENCE_QUESTION, List.of(), true, "router"));
        }
        return plan;
    }

    private RetrievalPlan counted(RetrievalPlan plan) {
        metrics.counter("kwiki_route_total", "source", plan.source().name()).increment();
        if (plan.fallbackReason() != null) {
            metrics.counter("kwiki_route_fallback_total", "reason", plan.fallbackReason())
                    .increment();
        }
        return plan;
    }
}
