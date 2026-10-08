package com.kwiki.rag.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RuleFirstRouterContextTest {

    private static final PriorRoute PRIOR_RETRIEVAL =
            new PriorRoute(Intent.KNOWLEDGE_QA, RouteSource.RULE, true, 1.0);

    /** 记录调用次数与收到的上下文的假路由 LLM。 */
    private static final class FakeLlm implements RouterLlmPort {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<RouteContext> seen = new AtomicReference<>();
        Optional<Map<String, Object>> response = Optional.empty();

        @Override
        public Optional<Map<String, Object>> askRouter(String query, String trace) {
            return askRouter(query, trace, RouteContext.EMPTY);
        }

        @Override
        public Optional<Map<String, Object>> askRouter(String query, String trace, RouteContext context) {
            calls.incrementAndGet();
            seen.set(context);
            return response;
        }
    }

    private static RuleFirstRouter router(RouterLlmPort port, boolean enabled) {
        ObjectProvider<RouterLlmPort> provider = new ObjectProvider<>() {
            @Override
            public RouterLlmPort getIfAvailable() {
                return port;
            }
        };
        return new RuleFirstRouter(KeywordRuleSet.defaults(), provider, enabled,
                new SimpleMeterRegistry(), 0.5);
    }

    private static Map<String, Object> decision(boolean needsRetrieval, double confidence,
                                                Map<String, Object> clarify) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("intent", needsRetrieval ? "KNOWLEDGE_QA" : "DIRECT_ANSWER");
        decision.put("needsRetrieval", needsRetrieval);
        decision.put("rewriteMode", "NONE");
        decision.put("subqueries", List.of());
        decision.put("confidence", confidence);
        if (clarify != null) {
            decision.put("clarify", clarify);
        }
        return decision;
    }

    private static RouteContext history(PriorRoute prior, boolean resumed, String... queries) {
        return new RouteContext(prior, List.of(queries), resumed);
    }

    @Test
    void greetingWithPunctuationIsDirectWithoutRetrieval() {
        RetrievalPlan plan = router(null, false).route("你好！");

        assertThat(plan.source()).isEqualTo(RouteSource.RULE);
        assertThat(plan.intent()).isEqualTo(Intent.DIRECT_ANSWER);
        assertThat(plan.needsRetrieval()).isFalse();
        assertThat(plan.matchedRuleIds()).anyMatch(id -> id.startsWith("greet-"));
        assertThat(router(null, false).route("help").needsRetrieval()).isFalse();
    }

    @Test
    void anaphoraWithoutHistoryAsksUserWithoutCallingTheLlm() {
        FakeLlm llm = new FakeLlm();

        RetrievalPlan plan = router(llm, true).route("它怎么用？", RouteContext.EMPTY);

        assertThat(plan.needsClarification()).isTrue();
        assertThat(plan.clarify().allowFreeText()).isTrue();
        assertThat(plan.clarify().options()).isEmpty();
        assertThat(plan.matchedRuleIds()).containsExactly("anaphora-no-context");
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void anaphoraWithHistoryEscalatesToTheLlmAndPassesContext() {
        FakeLlm llm = new FakeLlm();
        llm.response = Optional.of(decision(true, 0.9, null));
        RouteContext context = history(PRIOR_RETRIEVAL, false, "介绍一下知识库");

        RetrievalPlan plan = router(llm, true).route("它怎么用？", context);

        assertThat(plan.source()).isEqualTo(RouteSource.LLM);
        assertThat(plan.needsClarification()).isFalse();
        assertThat(llm.calls.get()).isEqualTo(1);
        assertThat(llm.seen.get().prior()).isEqualTo(PRIOR_RETRIEVAL);
        assertThat(llm.seen.get().recentUserQueries()).containsExactly("介绍一下知识库");
    }

    @Test
    void anaphoraWithHistoryAndLlmDisabledFallsBackToRetrieval() {
        RetrievalPlan plan = router(null, false).route("它怎么用？",
                history(PRIOR_RETRIEVAL, false, "介绍一下知识库"));

        assertThat(plan.source()).isEqualTo(RouteSource.FALLBACK);
        assertThat(plan.needsRetrieval()).isTrue();
        assertThat(plan.needsClarification()).isFalse();
        assertThat(plan.fallbackReason()).contains("anaphora-with-context");
    }

    @Test
    void resumedRunNeverClarifiesAgain() {
        FakeLlm llm = new FakeLlm();
        llm.response = Optional.of(decision(true, 0.2, Map.of("question", "还要补充吗？", "allowFreeText", true)));
        RouteContext resumed = history(PRIOR_RETRIEVAL, true, "介绍一下知识库");

        RetrievalPlan anaphora = router(llm, true).route("它怎么用？", resumed);
        RetrievalPlan viaLlm = router(llm, true).route("随便聊聊公司最近的动静", resumed);

        assertThat(anaphora.needsClarification()).isFalse();
        assertThat(anaphora.needsRetrieval()).isTrue();
        assertThat(viaLlm.needsClarification()).isFalse();
    }

    @Test
    void ruleResultThatChangesRetrievalNeedConsultsTheLlm() {
        FakeLlm llm = new FakeLlm();
        llm.response = Optional.of(decision(false, 0.9, null));

        RetrievalPlan plan = router(llm, true).route("你好", history(PRIOR_RETRIEVAL, false));

        assertThat(llm.calls.get()).isEqualTo(1);
        assertThat(plan.source()).isEqualTo(RouteSource.LLM);
    }

    @Test
    void unresolvedRetrievalNeedConflictKeepsTheRuleResult() {
        RetrievalPlan disabled = router(null, false).route("你好", history(PRIOR_RETRIEVAL, false));
        FakeLlm down = new FakeLlm();
        RetrievalPlan unavailable = router(down, true).route("你好", history(PRIOR_RETRIEVAL, false));

        for (RetrievalPlan plan : List.of(disabled, unavailable)) {
            assertThat(plan.source()).isEqualTo(RouteSource.RULE);
            assertThat(plan.intent()).isEqualTo(Intent.DIRECT_ANSWER);
            assertThat(plan.needsRetrieval()).isFalse();
        }
        assertThat(down.calls.get()).isEqualTo(1);
    }

    @Test
    void consistentRuleResultSkipsTheLlmEvenWithPriorRoute() {
        FakeLlm llm = new FakeLlm();

        RetrievalPlan plan = router(llm, true).route("什么是知识库", history(PRIOR_RETRIEVAL, false));

        assertThat(plan.source()).isEqualTo(RouteSource.RULE);
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void llmClarifyIsKeptAndLowConfidenceBecomesGenericClarification() {
        FakeLlm llm = new FakeLlm();
        llm.response = Optional.of(decision(true, 0.9, Map.of(
                "question", "你想了解哪一个？",
                "options", List.of(Map.of("id", "a", "label", "部署"), Map.of("id", "b", "label", "运维")))));
        RetrievalPlan withOptions = router(llm, true).route("随便聊聊公司最近的动静");
        assertThat(withOptions.needsClarification()).isTrue();
        assertThat(withOptions.clarify().options()).hasSize(2);

        llm.response = Optional.of(decision(true, 0.3, null));
        RetrievalPlan lowConfidence = router(llm, true).route("随便聊聊公司最近的动静");
        assertThat(lowConfidence.needsClarification()).isTrue();
        assertThat(lowConfidence.clarify().options()).isEmpty();
        assertThat(lowConfidence.clarify().allowFreeText()).isTrue();

        llm.response = Optional.of(decision(true, 0.8, null));
        assertThat(router(llm, true).route("随便聊聊公司最近的动静").needsClarification()).isFalse();

        llm.response = Optional.of(decision(true, 0.0, null));
        assertThat(router(llm, true).route("随便聊聊公司最近的动静").needsClarification())
                .as("未提供置信度（0.0）不触发反问").isFalse();
    }
}
