package com.kwiki.rag.routing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleFirstRouterTest {

    private static final ObjectProvider<RouterLlmPort> NO_LLM = new ObjectProvider<>() {
        @Override
        public RouterLlmPort getIfAvailable() {
            return null;
        }
    };

    private RouterLlmPort recordingFake(Map<String, Object> response, AtomicInteger calls) {
        return (query, trace) -> {
            calls.incrementAndGet();
            return Optional.ofNullable(response);
        };
    }

    private RuleFirstRouter router(RouterLlmPort port, boolean llmEnabled) {
        ObjectProvider<RouterLlmPort> provider = new ObjectProvider<>() {
            @Override
            public RouterLlmPort getIfAvailable() {
                return port;
            }
        };
        return new RuleFirstRouter(KeywordRuleSet.defaults(), provider, llmEnabled,
                new SimpleMeterRegistry());
    }

    @ParameterizedTest
    @CsvSource({
            "什么是知识库, KNOWLEDGE_QA",
            "什么是向量数据库, KNOWLEDGE_QA",
            "how to deploy the wiki, PROCEDURAL",
            "怎么配置单点登录, PROCEDURAL",
            "对比 mysql 和 es 的区别, ANALYTICAL",
            "你好, DIRECT_ANSWER",
            "hello, DIRECT_ANSWER"
    })
    void singleTerminalRuleMatchSkipsTheLlm(String query, Intent expectedIntent) {
        AtomicInteger llmCalls = new AtomicInteger();
        RuleFirstRouter router = router(recordingFake(null, llmCalls), true);

        RetrievalPlan plan = router.route(query);

        assertThat(plan.source()).isEqualTo(RouteSource.RULE);
        assertThat(plan.intent()).isEqualTo(expectedIntent);
        assertThat(plan.ruleVersion()).isEqualTo(KeywordRuleSet.VERSION);
        assertThat(plan.matchedRuleIds()).isNotEmpty();
        assertThat(llmCalls.get()).as("LLM must not be called on terminal rule match").isZero();
    }

    @Test
    void conflictingIntentsConsultTheLlm() {
        AtomicInteger llmCalls = new AtomicInteger();
        // 同时匹配过程型（怎么）与分析型（区别）关键词
        RuleFirstRouter router = router(
                recordingFake(validDecision(Intent.PROCEDURAL), llmCalls), true);

        RetrievalPlan plan = router.route("mysql 和 es 怎么选，区别是什么？");

        assertThat(plan.source()).isEqualTo(RouteSource.LLM);
        assertThat(llmCalls.get()).isEqualTo(1);
    }

    @Test
    void noMatchAndCompoundQueriesConsultTheLlm() {
        AtomicInteger llmCalls = new AtomicInteger();
        RuleFirstRouter router = router(
                recordingFake(validDecision(Intent.KNOWLEDGE_QA), llmCalls), true);

        router.route("随便聊聊公司最近的动静");
        router.route("什么是知识库？另外怎么导出页面？");

        assertThat(llmCalls.get()).isEqualTo(2);
    }

    @Test
    void llmDisabledFallsBackToScopedKnowledgeQa() {
        AtomicInteger llmCalls = new AtomicInteger();
        RuleFirstRouter router = router(recordingFake(validDecision(Intent.ANALYTICAL), llmCalls),
                false);

        RetrievalPlan plan = router.route("随便聊聊公司最近的动静");

        assertThat(plan.source()).isEqualTo(RouteSource.FALLBACK);
        assertThat(plan.intent()).isEqualTo(Intent.KNOWLEDGE_QA);
        assertThat(plan.needsRetrieval()).isTrue();
        assertThat(plan.fallbackReason()).contains("llm-disabled");
        assertThat(llmCalls.get()).isZero();
    }

    @Test
    void invalidLlmOutputFallsBack() {
        Map<String, Object> bad = validDecision(Intent.KNOWLEDGE_QA);
        bad.put("esQuery", "{\"query\":{\"bool\":{}}}");
        RuleFirstRouter router = router(recordingFake(bad, new AtomicInteger()), true);

        RetrievalPlan plan = router.route("随便聊聊公司最近的动静");

        assertThat(plan.source()).isEqualTo(RouteSource.FALLBACK);
        assertThat(plan.fallbackReason()).isEqualTo("llm-invalid-output");
    }

    @Test
    void unavailableLlmFallsBack() {
        RuleFirstRouter router = router((query, trace) -> Optional.empty(), true);

        RetrievalPlan plan = router.route("随便聊聊公司最近的动静");

        assertThat(plan.source()).isEqualTo(RouteSource.FALLBACK);
        assertThat(plan.fallbackReason()).isEqualTo("llm-unavailable");
    }

    @Test
    void routerWithoutLlmBeanFallsBackDeterministically() {
        RuleFirstRouter router = new RuleFirstRouter(KeywordRuleSet.defaults(), NO_LLM, true,
                new SimpleMeterRegistry());

        assertThat(router.route("随便聊聊").source()).isEqualTo(RouteSource.FALLBACK);
        assertThat(router.route("什么是知识库").source()).isEqualTo(RouteSource.RULE);
    }

    @Test
    void normalizationLowercasesLatinAndKeepsCjk() {
        assertThat(QueryNormalizer.normalize("  What   Is kwiki? "))
                .isEqualTo("what is kwiki?");
        assertThat(QueryNormalizer.normalize(null)).isEmpty();
    }

    private static Map<String, Object> validDecision(Intent intent) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("intent", intent.name());
        decision.put("needsRetrieval", true);
        decision.put("rewriteMode", "NONE");
        decision.put("subqueries", java.util.List.of());
        decision.put("confidence", 0.8);
        return decision;
    }

    @Test
    void validatorAcceptsWellFormedDecision() {
        RetrievalPlan plan = RouterDecisionValidator.validate(validDecision(Intent.PROCEDURAL));
        assertThat(plan.source()).isEqualTo(RouteSource.LLM);
        assertThat(plan.intent()).isEqualTo(Intent.PROCEDURAL);
    }

    @Test
    void validatorRejectsEveryDisallowedBranch() {
        Map<String, Object> unknown = validDecision(Intent.KNOWLEDGE_QA);
        unknown.put("graphTool", "cypher");
        assertThatThrownBy(() -> RouterDecisionValidator.validate(unknown))
                .hasMessageContaining("unknown field");

        Map<String, Object> graphIntent = validDecision(Intent.KNOWLEDGE_QA);
        graphIntent.put("intent", "GRAPH_QA");
        assertThatThrownBy(() -> RouterDecisionValidator.validate(graphIntent))
                .hasMessageContaining("unsupported intent");

        Map<String, Object> dsl = validDecision(Intent.KNOWLEDGE_QA);
        dsl.put("subqueries", java.util.List.of("{\"query\":{\"bool\":{\"must\":[]}}}"));
        assertThatThrownBy(() -> RouterDecisionValidator.validate(dsl))
                .hasMessageContaining("ES DSL");

        Map<String, Object> credential = validDecision(Intent.KNOWLEDGE_QA);
        credential.put("subqueries", java.util.List.of("use sk-abcdef123456789 to answer"));
        assertThatThrownBy(() -> RouterDecisionValidator.validate(credential))
                .hasMessageContaining("credential");

        Map<String, Object> scope = validDecision(Intent.KNOWLEDGE_QA);
        scope.put("subqueries", java.util.List.of("filter kbId=3"));
        assertThatThrownBy(() -> RouterDecisionValidator.validate(scope))
                .hasMessageContaining("authorization scope");

        Map<String, Object> blank = validDecision(Intent.KNOWLEDGE_QA);
        blank.put("subqueries", java.util.List.of("  "));
        assertThatThrownBy(() -> RouterDecisionValidator.validate(blank))
                .hasMessageContaining("nonblank");

        Map<String, Object> tooMany = validDecision(Intent.KNOWLEDGE_QA);
        tooMany.put("subqueries", java.util.List.of("a", "b", "c", "d"));
        assertThatThrownBy(() -> RouterDecisionValidator.validate(tooMany))
                .hasMessageContaining("too many subqueries");

        Map<String, Object> badConfidence = validDecision(Intent.KNOWLEDGE_QA);
        badConfidence.put("confidence", 1.7);
        assertThatThrownBy(() -> RouterDecisionValidator.validate(badConfidence))
                .hasMessageContaining("confidence");
    }
}
