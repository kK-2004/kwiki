package com.kwiki.rag.routing;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Rule-first intent routing: a single terminal rule match bypasses the LLM
 * entirely; no-match, conflicts, and compound queries consult the (schema-bound)
 * router LLM; every LLM failure mode degrades to scoped KNOWLEDGE_QA with a
 * recorded fallback reason. Metrics track source distribution and fallback rate.
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
        KeywordRuleSet.MatchResult match = rules.match(normalized);
        if (match instanceof KeywordRuleSet.MatchResult.Resolved resolved) {
            return counted(RetrievalPlan.rule(resolved.intent(), rules.version(),
                    resolved.ruleIds()));
        }
        KeywordRuleSet.MatchResult.NeedsLlm needsLlm = (KeywordRuleSet.MatchResult.NeedsLlm) match;
        if (!llmEnabled || llm == null) {
            return counted(RetrievalPlan.fallback(
                    "llm-disabled:" + needsLlm.reason()));
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
