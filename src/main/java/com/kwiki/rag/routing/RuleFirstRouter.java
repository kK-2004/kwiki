package com.kwiki.rag.routing;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 规则优先的意图路由：命中单条终止性规则即可完全绕过
 * LLM；无命中、冲突以及复合查询则咨询（受 schema 约束的）
 * 路由器 LLM；每一种 LLM 失败情形都会降级为带作用域的 KNOWLEDGE_QA，
 * 并记录回退原因。指标跟踪来源分布与回退率。
 */
@Component
public class RuleFirstRouter {

    private final KeywordRuleSet rules;
    private final RouterLlmPort llm;
    private final boolean llmEnabled;
    private final MeterRegistry metrics;

    public RuleFirstRouter(KeywordRuleSet rules,
                           ObjectProvider<RouterLlmPort> llm,
                           @Value("${kwiki.routing.llm-enabled:false}") boolean llmEnabled,
                           MeterRegistry metrics) {
        this.rules = rules;
        this.llm = llm.getIfAvailable();
        this.llmEnabled = llmEnabled;
        this.metrics = metrics;
    }

    public RetrievalPlan route(String rawQuery) {
        long start = System.nanoTime();
        RetrievalPlan plan = routeInternal(rawQuery);
        io.micrometer.core.instrument.Timer.builder("kwiki_route_latency")
                .description("intent routing latency")
                .register(metrics)
                .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        return counted(plan);
    }

    private RetrievalPlan routeInternal(String rawQuery) {
        String normalized = QueryNormalizer.normalize(rawQuery);
        return switch (rules.match(normalized)) {
            case KeywordRuleSet.MatchResult.Resolved resolved ->
                    counted(RetrievalPlan.rule(resolved.intent(), rules.version(),
                            resolved.ruleIds()));
            case KeywordRuleSet.MatchResult.NeedsLlm needsLlm ->
                    routeWithLlm(normalized, needsLlm);
        };
    }

    private RetrievalPlan routeWithLlm(String normalized,
                                       KeywordRuleSet.MatchResult.NeedsLlm needsLlm) {
        if (!llmEnabled || llm == null) {
            return counted(RetrievalPlan.fallback("llm-disabled:" + needsLlm.reason()));
        }
        try {
            Optional<Map<String, Object>> decision = llm.askRouter(normalized, needsLlm.reason());
            if (decision.isEmpty()) {
                return counted(RetrievalPlan.fallback("llm-unavailable"));
            }
            return counted(RouterDecisionValidator.validate(decision.get()));
        } catch (IllegalArgumentException invalid) {
            return counted(RetrievalPlan.fallback("llm-invalid-output"));
        } catch (Exception failure) {
            return counted(RetrievalPlan.fallback("llm-error"));
        }
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
