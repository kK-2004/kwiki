package com.kwiki.rag.rewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.rag.routing.Intent;
import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.routing.RewriteMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改写契约：在固定的回归语料上进行表驱动的策略选择、
 * 带确定性兜底的对话补全、受守卫保护的扩展、
 * 有界分解，以及编排器安全性（空白/失败的改写绝不
 * 中止检索；去重保持原有顺序）。
 */
class QueryRewriteTest {

    private static final io.micrometer.core.instrument.MeterRegistry METRICS =
            new SimpleMeterRegistry();

    private final RewriteDecisionService decisions = new RewriteDecisionService();

    private QueryRewriteOrchestrator orchestrator(RewriteLlmPort port) {
        Optional<RewriteLlmPort> optional = Optional.ofNullable(port);
        return new QueryRewriteOrchestrator(decisions,
                new ConversationalRewriter(optional),
                new ExpansionRewriter(optional),
                new DecompositionRewriter(optional), METRICS);
    }

    private static RetrievalPlan plan(RewriteMode mode) {
        return new RetrievalPlan(Intent.KNOWLEDGE_QA, true, mode, List.of(),
                com.kwiki.rag.routing.RouteSource.RULE, "rules-v1", List.of(), null, 1.0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            什么是知识库|NONE|NONE|完整问题无需改写
            它支持哪些数据库|USER:什么是kwiki知识库|CONVERSATIONAL|指代加历史触发补全
            the above settings|USER:how to configure redis|CONVERSATIONAL|英文指代触发补全
            部署|NONE|EXPANSION|过短低信息查询扩展
            sso|NONE|EXPANSION|过短低信息查询扩展
            kwiki release 3.2 notes|NONE|NONE|带版本号信息量足够
            那个东西多少钱|NONE|NONE|疑问词信息量足够且无历史不触发补全
            """)
    void corpusRowsSelectTheExpectedMode(String query, String history,
                                         RewriteMode expected, String note) {
        List<ChatTurn> turns = "NONE".equals(history) ? List.of()
                : List.of(new ChatTurn("USER", history.substring("USER:".length())));
        assertThat(decisions.decide(plan(RewriteMode.NONE), query, turns))
                .as(note).isEqualTo(expected);
    }

    @Test
    void routerDecompositionPlanAlwaysWins() {
        assertThat(decisions.decide(plan(RewriteMode.DECOMPOSITION), "什么是知识库？另外怎么导出页面", List.of()))
                .isEqualTo(RewriteMode.DECOMPOSITION);
    }

    @Test
    void conversationalRewriteUsesLlmOutputWhenAvailable() {
        QueryRewriteOrchestrator withLlm = orchestrator((system, prompt) ->
                Optional.of("kwiki 支持哪些外部数据库？"));

        RewriteResult result = withLlm.rewrite("它支持哪些数据库",
                plan(RewriteMode.NONE), List.of(new ChatTurn("USER", "什么是 kwiki 知识库")));

        assertThat(result.mode()).isEqualTo(RewriteMode.CONVERSATIONAL);
        assertThat(result.effectiveQueries()).containsExactly("kwiki 支持哪些外部数据库？");
        assertThat(result.original()).isEqualTo("它支持哪些数据库");
    }

    @Test
    void missingHistoryFallsBackWithoutContextInjection() {
        RewriteResult result = new ConversationalRewriter(Optional.empty())
                .rewrite("它支持哪些数据库", List.of());

        assertThat(result.effectiveQueries()).containsExactly("它支持哪些数据库");
        assertThat(result.fallbackReason()).isEqualTo("no-history");
    }

    @Test
    void oversizedHistoryIsBounded() {
        List<ChatTurn> bigHistory = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> new ChatTurn("USER", "turn " + i))
                .toList();
        ConversationalRewriter rewriter = new ConversationalRewriter(Optional.empty());

        rewriter.rewrite("它怎么配置", bigHistory.subList(0, 20));

        // 确定性兜底只使用最近一轮用户输入；不抛异常，成本有界
        RewriteResult result = rewriter.rewrite("它怎么配置", bigHistory);
        assertThat(result.effectiveQueries()).hasSize(1);
        assertThat(result.effectiveQueries().get(0)).contains("turn 49");
    }

    @Test
    void expansionGuardRejectsEntityChangesAndInjections() {
        QueryRewriteOrchestrator withLlm = orchestrator((system, prompt) ->
                Optional.of("kwiki 部署步骤以及 kbId=1 的权限"));

        RewriteResult result = withLlm.rewrite("kwiki 部署", plan(RewriteMode.NONE), List.of());

        assertThat(result.effectiveQueries()).containsExactly("kwiki 部署");
        assertThat(result.fallbackReason()).isEqualTo("expansion-guard-rejected");
    }

    @Test
    void expansionKeepsNamedEntities() {
        QueryRewriteOrchestrator withLlm = orchestrator((system, prompt) ->
                Optional.of("kwiki 部署 步骤 清单"));

        RewriteResult result = withLlm.rewrite("kwiki 部署", plan(RewriteMode.NONE), List.of());

        assertThat(result.mode()).isEqualTo(RewriteMode.EXPANSION);
        assertThat(result.effectiveQueries()).containsExactly("kwiki 部署 步骤 清单");
    }

    @Test
    void decompositionProducesBoundedDistinctSubqueries() {
        QueryRewriteOrchestrator withLlm = orchestrator((system, prompt) ->
                Optional.of("什么是知识库\n怎么导出页面\n怎么导出页面"));

        RewriteResult result = withLlm.rewrite("什么是知识库？另外怎么导出页面",
                plan(RewriteMode.DECOMPOSITION), List.of());

        assertThat(result.mode()).isEqualTo(RewriteMode.DECOMPOSITION);
        assertThat(result.effectiveQueries()).containsExactly("什么是知识库？", "怎么导出页面");
        assertThat(result.fallbackReason())
                .as("duplicate LLM lines are rejected, deterministic split takes over")
                .isEqualTo("deterministic-split");
    }

    @Test
    void deterministicDecompositionSplitsOnConjunctions() {
        RewriteResult result = orchestrator(null).rewrite(
                "什么是知识库？另外怎么导出页面", plan(RewriteMode.DECOMPOSITION), List.of());

        assertThat(result.effectiveQueries()).containsExactly("什么是知识库？", "怎么导出页面");
    }

    @Test
    void tooManyLlmSubqueriesFallBackToDeterministicSplit() {
        QueryRewriteOrchestrator withLlm = orchestrator((system, prompt) ->
                Optional.of("a\nb\nc\nd"));

        RewriteResult result = withLlm.rewrite("什么是知识库；然后怎么导出；还有权限怎么配",
                plan(RewriteMode.DECOMPOSITION), List.of());

        assertThat(result.effectiveQueries()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void blankQueryNeverAbortsRetrieval() {
        RewriteResult result = orchestrator((system, prompt) -> Optional.of("")).rewrite(
                "   ", plan(RewriteMode.NONE), List.of());

        assertThat(result.fallbackReason()).isEqualTo("blank-query");
        assertThat(result.effectiveQueries()).containsExactly("");
    }

    @Test
    void failingRewriteProviderFallsBackToOriginal() {
        QueryRewriteOrchestrator failing = orchestrator((system, prompt) -> {
            throw new IllegalStateException("provider down");
        });

        RewriteResult result = failing.rewrite("部署", plan(RewriteMode.NONE), List.of());

        assertThat(result.effectiveQueries()).containsExactly("部署");
    }

    @Test
    void baselineMetricsFixtureMatchesCorpus() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/rewrite/baseline-metrics.json")) {
            Map<String, Object> baseline = new ObjectMapper().readValue(in, Map.class);
            try (InputStream corpus = getClass().getResourceAsStream("/rewrite/rewrite-corpus.csv")) {
                List<String> rows = new String(corpus.readAllBytes(), StandardCharsets.UTF_8)
                        .lines().filter(line -> line.contains("|") && !line.startsWith("query"))
                        .collect(Collectors.toList());
                assertThat(rows).as("corpus rows").hasSize(((Number) baseline.get("cases")).intValue());
            }
            assertThat(baseline.get("excludedBehaviors")).asList()
                    .anySatisfy(item -> assertThat(String.valueOf(item)).contains("HyDE"));
        }
    }
}
