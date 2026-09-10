package com.kwiki.rag.orchestration;

import static org.assertj.core.api.Assertions.*;

import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.answer.CandidateAnswer;
import com.kwiki.rag.quality.QualityV2Input;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.AgenticTestSupport.Harness;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 以脚本化叶子适配器验证 QA 门禁状态机行为：阶段顺序、
 * 每阶段重新生成 + 评审、预算上限、精确的拒绝文案以及
 * 基础设施终止态。未经评审的候选绝不能上线。
 */
class AgenticWorkflowTest {

    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());
    Harness harness;

    @AfterEach
    void close() {
        // 测试脚手架只使用脚本化适配器；无需关闭任何东西
    }

    Harness harness() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(),
                StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class),
                versions,
                new com.kwiki.infrastructure.redis.ScopeCache(
                        StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        harness = AgenticTestSupport.harness(
                router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
        return harness;
    }

    List<ChatStreamEvent> run(Harness harness, String query) {
        return harness.workflow.answer(user, query)
                .collectList()
                .block(Duration.ofSeconds(10));
    }
    static ChunkHit hit(String key, String parent, String content) {
        return new ChunkHit(key, parent, 1, "PAGE", 1, 1L, "heading", 0, content.length(), content);
    }

    void stubCorpus(Harness harness, List<ChunkHit> children) {
        harness.bm25.hits = children;
        harness.vector.hits = children;
    }

    void stubParent(Harness harness, String parentKey, String content) {
        harness.parents.byKey.put(parentKey, new ParentEvidenceChunk(
                parentKey, 1, "PAGE", 1, 1L, "heading", content, 0,
                List.copyOf(harness.bm25.hits)));
    }

    // ------------------------------------------------------------------
    // 阶段顺序与立即停止
    // ------------------------------------------------------------------

    @Test
    void childCandidatePassingGatePublishesWithoutParentFetchOrRewrite() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "候选依据 [P0]")));
        harness.answer.scripted.add("基于证据的回答 [P0]");
        harness.quality.passes = input -> true;

        var events = run(harness, "什么是部署方式");

        assertThat(harness.parents.requestedKeys).isEmpty(); // 首个 QA 门禁：不读取父分块正文
        assertThat(harness.rewriter.inputs).isEmpty();
        assertThat(harness.answer.prompts).hasSize(1);
        assertThat(harness.quality.inputs).hasSize(1);
        assertThat(events.stream().filter(event -> event.type().equals("token"))
                .map(event -> String.valueOf(event.payloadMap().get("text")))
                .collect(Collectors.joining()))
                .isEqualTo("基于证据的回答 [P0]");
        var done = events.getLast();
        assertThat(done.type()).isEqualTo("done");
        assertThat(done.payloadMap()).containsEntry("outcome", "completed");
        assertThat(events.stream().filter(event -> event.type().equals("citations"))).hasSize(1);
    }

    @Test
    void rejectedChildRecoversThroughParentStageWithoutExpansionOrRewrite() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段正文 [P0]")));
        stubParent(harness, "P1", "完整父段落正文 [P0]");
        // 子分块候选失败，父分块候选通过
        harness.quality.passes =
                input -> input.candidate().evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT;
        harness.answer.scripted.add("子候选 [P0]");
        harness.answer.scripted.add("父候选完整回答 [P0]");

        var events = run(harness, "什么是部署方式");

        assertThat(harness.parents.requestedKeys).hasSize(1);
        assertThat(harness.rewriter.inputs).isEmpty();
        // 扩展阶段从未运行：所有召回的 TopK 都是基础值 20
        assertThat(harness.bm25.topKs).containsOnly(20);
        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed");
        assertThat(events.stream().filter(event -> event.type().equals("token"))
                .map(event -> String.valueOf(event.payloadMap().get("text")))
                .collect(Collectors.joining()))
                .isEqualTo("父候选完整回答 [P0]");
    }

    @Test
    void expansionWidensBothBranchesAndFinalTopK() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        // 只有扩展后的子分块阶段通过
        harness.quality.passes = input -> input.candidate().attemptStage()
                == AttemptStage.EXPANDED_CHILD;
        harness.answer.scripted.add("基础子候选");
        harness.answer.scripted.add("基础父候选");
        harness.answer.scripted.add("扩大检索候选 [P0]");

        var events = run(harness, "什么是部署方式");

        // 父分块阶段获取的是父分块而非子分块：base(20) → expanded(50)
        assertThat(harness.bm25.topKs).containsExactly(20, 50);
        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed");
        assertThat(events.getLast().payloadMap()).containsEntry("attemptStage", "EXPANDED_CHILD");
    }

    @Test
    void zeroHitsSkipGenerationAndAdvanceToExpansion() {
        var harness = harness();
        stubCorpus(harness, List.of()); // 两个分支都成功但命中为零
        harness.quality.passes = input -> true;

        run(harness, "什么是部署方式");

        assertThat(harness.answer.prompts).isEmpty(); // 绝不让模型空转
        // 零命中轮次会先扩展，再改写（两次）：3 轮 × base+expanded
        assertThat(harness.bm25.topKs).containsExactly(20, 50, 20, 50, 20, 50);
    }

    // ------------------------------------------------------------------
    // 有界轮次与精确拒绝
    // ------------------------------------------------------------------

    @Test
    void exhaustedRoundsOutputExactRefusalWithEmptyCitations() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        stubParent(harness, "P1", "父正文 [P0]");
        harness.quality.passes = input -> false; // 每个候选都失败
        harness.answer.scripted.add("1");
        harness.answer.scripted.add("2");
        harness.answer.scripted.add("3");
        harness.answer.scripted.add("4");
        harness.answer.scripted.add("5");
        harness.answer.scripted.add("6");

        var events = run(harness, "什么是部署方式");

        assertThat(harness.rewriter.inputs).hasSize(2); // 恰好两次改写调用
        assertThat(harness.bm25.topKs).hasSize(6);      // 3 轮 × base+expanded 检索……有界
        var done = events.getLast();
        assertThat(done.type()).isEqualTo("done");
        assertThat(done.payloadMap()).containsEntry("outcome", "insufficient");
        assertThat(done.payloadMap()).containsEntry("noEvidence", true);
        assertThat(events.stream().filter(event -> event.type().equals("token"))
                .map(event -> String.valueOf(event.payloadMap().get("text")))
                .collect(Collectors.joining()))
                .isEqualTo(AgenticErrorCodes.INSUFFICIENT_MESSAGE);
        var citations = events.stream()
                .filter(event -> event.type().equals("citations")).findFirst().orElseThrow();
        assertThat(citations.payloadMap().get("citations"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isEmpty();
    }

    @Test
    void invalidRewriteConsumesBudgetAndRefusesWhenExhausted() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        stubParent(harness, "P1", "父正文");
        harness.quality.passes = input -> false;
        // 两次改写调用都返回无效的原样回显
        harness.rewriter.scripted.add(Optional.empty());
        harness.rewriter.scripted.add(Optional.empty());

        var events = run(harness, "什么是部署方式");

        assertThat(harness.rewriter.inputs).hasSize(2);
        // 无效改写绝不重复执行相同的检索：只有第 1 轮的阶段运行过
        assertThat(harness.bm25.queries.stream().distinct().count()).isEqualTo(1);
        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "insufficient");
    }

    @Test
    void rewriteInputCarriesCompleteFeedbackContext() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        stubParent(harness, "P1", "父正文");
        harness.quality.passes = input -> false;
        harness.rewriter.scripted.add(Optional.of("第一次改写的问题"));
        harness.rewriter.scripted.add(Optional.of("第二次改写的问题"));

        run(harness, "什么是部署方式");

        var second = harness.rewriter.inputs.getLast();
        assertThat(second.originalQuery()).isEqualTo("什么是部署方式");
        assertThat(second.lastQuery()).isEqualTo("第一次改写的问题");
        assertThat(second.previousRewrites()).containsExactly("第一次改写的问题");
        assertThat(second.topKBefore()).isEqualTo(harness.budgets.base().finalTopK());
        assertThat(second.topKAfter()).isEqualTo(harness.budgets.expanded().finalTopK());
        assertThat(second.expansionAttempted()).isTrue();
        assertThat(second.stageFailures()).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // 基础设施终止态与候选隔离
    // ------------------------------------------------------------------

    @Test
    void qaSchemaFailureIsAnInfrastructureTerminalNotARefusal() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        harness.quality.unavailable = true;

        var events = run(harness, "什么是部署方式");

        assertThat(events.getLast().type()).isEqualTo("error");
        assertThat(events.getLast().payloadMap()).containsEntry("error", "qa-unavailable");
        assertThat(events.stream().filter(event -> event.type().equals("token"))).isEmpty();
    }

    @Test
    void unreviewedCandidateNeverStreamsToTheClient() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        stubParent(harness, "P1", "父正文 [P0]");
        harness.quality.passes = input ->
                input.candidate().evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT;
        harness.answer.scripted.add("未通过的子候选，绝不能出现在流里");
        harness.answer.scripted.add("通过的父候选 [P0]");

        var events = run(harness, "什么是部署方式");

        var streamed = events.stream().filter(event -> event.type().equals("token"))
                .map(event -> String.valueOf(event.payloadMap().get("text")))
                .collect(Collectors.joining());
        assertThat(streamed).contains("通过的父候选");
        assertThat(streamed).doesNotContain("绝不能出现在流里");
    }

    @Test
    void candidateCitationsValidateAgainstEvidence() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段内容 [P0]")));
        harness.answer.scripted.add("引用了证据 [P0]");
        harness.quality.passes = input -> true;

        var events = run(harness, "什么是部署方式");

        var citations = events.stream()
                .filter(event -> event.type().equals("citations")).findFirst().orElseThrow();
        var list = (List<?>) citations.payloadMap().get("citations");
        assertThat(list).hasSize(1);
        assertThat(((Map<?, ?>) list.get(0)).get("childChunkKey")).isEqualTo("C1");
        assertThat(events.getLast().payloadMap().get("citationCount")).isEqualTo(1);
    }

    @Test
    void worstQualityPathFitsBudgetCounters() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        stubParent(harness, "P1", "父正文");
        harness.quality.passes = input -> false;

        run(harness, "什么是部署方式");

        // 3 轮 ×（base child + base parent + expanded child）生成次数；
        // 在该语料上 expanded-parent 阶段会被跳过，因为父分块正文
        // 已在该轮的上下文中（父分块 key 相同）。
        assertThat(harness.answer.prompts).hasSize(9);
        assertThat(harness.quality.inputs).hasSize(9);
        assertThat(harness.bm25.topKs).hasSize(6);
        assertThat(harness.parents.requestedKeys).hasSize(6);
    }

    @Test
    void activityEventsTraceEveryExecutedStage() {
        var harness = harness();
        stubCorpus(harness, List.of(hit("C1", "P1", "片段 [P0]")));
        harness.answer.scripted.add("通过评审的回答 [P0]");
        harness.quality.passes = input -> true;

        var events = run(harness, "什么是部署方式");

        var activities = events.stream()
                .filter(event -> event.type().equals("activity"))
                .map(ChatStreamEvent::payloadMap)
                .toList();
        assertThat(activities).isNotEmpty();
        var phases = activities.stream().map(payload -> String.valueOf(payload.get("phase")));
        assertThat(phases).contains("ROUTE", "RETRIEVAL", "GENERATION", "QUALITY");
        // stepId 在同一步骤的 started/completed 之间保持稳定
        var startedIds = activities.stream()
                .filter(payload -> "STARTED".equals(payload.get("status")))
                .map(payload -> String.valueOf(payload.get("stepId"))).toList();
        var completedIds = activities.stream()
                .filter(payload -> "COMPLETED".equals(payload.get("status")))
                .map(payload -> String.valueOf(payload.get("stepId"))).toList();
        assertThat(completedIds).containsAll(startedIds);
        // 检索完成事件携带真实指标，绝不凭空捏造
        var retrieval = activities.stream()
                .filter(payload -> "RETRIEVAL".equals(payload.get("phase"))
                        && "COMPLETED".equals(payload.get("status")))
                .findFirst().orElseThrow();
        var metricsMap = (Map<?, ?>) retrieval.get("metrics");
        var metricKeys = metricsMap.keySet().stream().map(String::valueOf).toList();
        assertThat(metricKeys).contains("branchTopK", "finalTopK", "retainedChildCount");
        assertThat(metricsMap.get("retainedChildCount")).isEqualTo(1);
    }
}
