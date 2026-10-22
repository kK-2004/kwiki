package com.kwiki.rag.orchestration;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.AgenticTestSupport.Harness;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 工作流在子块召回、改写与生成处发出观测事件；观测者故障不影响回答。 */
class WorkflowRunObserverTest {
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());

    Harness harness() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(),
                StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class),
                versions,
                new com.kwiki.infrastructure.redis.ScopeCache(
                        StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        return AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
    }

    static ChunkHit hit(String key, String content) {
        return hit(key, 1, content);
    }

    static ChunkHit hit(String key, long pageId, String content) {
        return new ChunkHit(key, "P-" + key, 1, "PAGE", pageId, 1L, "heading", 0, content.length(), content);
    }

    static List<RunObserver.ContextObserved> contexts(List<RunObserver.Event> events) {
        return events.stream().filter(RunObserver.ContextObserved.class::isInstance)
                .map(RunObserver.ContextObserved.class::cast).toList();
    }

    @Test
    void successfulRunEmitsChildrenAndContext() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1 部署使用容器"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();

        var stream = harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, events::add).collectList().block(Duration.ofSeconds(10));

        assertThat(stream.getLast().payloadMap()).containsEntry("outcome", "completed");
        assertThat(events).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(RunObserver.ChildrenObserved.class,
                        children -> assertThat(children.chunkKeys()).contains("C1")));
        var contexts = events.stream().filter(RunObserver.ContextObserved.class::isInstance)
                .map(RunObserver.ContextObserved.class::cast).toList();
        assertThat(contexts).isNotEmpty();
        assertThat(contexts.getLast().context()).contains("候选依据 C1");
        assertThat(contexts.getLast().query()).isEqualTo("什么是部署方式");
    }

    @Test
    void childStageContextIsSplitIntoOneSegmentPerChildWithItsSource() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", 11, "甲公司排放量为10,000吨"), hit("C2", 22, "乙公司排放量为2万吨"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();

        harness.workflow.answerInSession(user, "排放量是多少", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, events::add).collectList().block(Duration.ofSeconds(10));

        var context = contexts(events).getFirst();
        assertThat(context.attemptStage()).isEqualTo("BASE_CHILD");
        assertThat(context.segments()).extracting(RunObserver.ContextSegment::resourceType).containsOnly("PAGE");
        assertThat(context.segments()).extracting(RunObserver.ContextSegment::resourceId)
                .containsExactlyInAnyOrder(11L, 22L);
        assertThat(context.segments()).allSatisfy(segment ->
                assertThat(context.context()).contains(segment.text()));
        assertThat(context.segments()).filteredOn(segment -> segment.resourceId() == 11L)
                .singleElement().extracting(RunObserver.ContextSegment::text).isEqualTo("甲公司排放量为10,000吨");
    }

    @Test
    void parentStageSegmentsCarryTheParentBodies() {
        var harness = harness();
        var child = hit("C1", 11, "甲公司排放量");
        harness.bm25.hits = List.of(child);
        harness.vector.hits = harness.bm25.hits;
        harness.parents.byKey.put("P-C1", new com.kwiki.rag.retrieval.ParentEvidenceChunk("P-C1", 1, "PAGE", 11,
                1L, "heading", "完整父段落：甲公司排放量为10,000吨", 0, List.of(child)));
        harness.quality.passes = input -> input.candidate().attemptStage() == AttemptStage.BASE_PARENT;
        harness.answer.scripted.add("子候选");
        harness.answer.scripted.add("父候选 [P0]");
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();

        harness.workflow.answerInSession(user, "排放量是多少", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, events::add).collectList().block(Duration.ofSeconds(10));

        var parent = contexts(events).stream().filter(context -> context.attemptStage().equals("BASE_PARENT"))
                .findFirst().orElseThrow();
        assertThat(parent.segments()).containsExactly(
                new RunObserver.ContextSegment("PAGE", 11, "完整父段落：甲公司排放量为10,000吨"));
        assertThat(parent.context()).contains("完整父段落：甲公司排放量为10,000吨");
    }

    @Test
    void feedbackRewriteEmitsRewriteEvents() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.quality.passes = input -> false;
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();

        harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, events::add).collectList().block(Duration.ofSeconds(10));

        long rewrites = events.stream().filter(RunObserver.RewriteObserved.class::isInstance).count();
        assertThat(harness.rewriter.inputs).isNotEmpty();
        // 每次成功改写恰好发一次事件；改写器可能因重试被多调用，故只断言上界
        assertThat(rewrites).isPositive().isLessThanOrEqualTo(harness.rewriter.inputs.size());
    }

    @Test
    void failingObserverDoesNotChangeTheAnswer() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var stream = harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, event -> { throw new IllegalStateException("boom"); })
                .collectList().block(Duration.ofSeconds(10));

        assertThat(stream.getLast().payloadMap()).containsEntry("outcome", "completed");
    }

    @Test
    void observerUsageSourceIsAppliedToTheRunContext() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");
        List<com.kwiki.usage.UsageSource> seen = new CopyOnWriteArrayList<>();
        RunObserver evalObserver = new RunObserver() {
            @Override
            public void onEvent(Event event) {
                RunContext current = RunContext.current();
                if (current != null) seen.add(current.usageSource());
            }

            @Override
            public com.kwiki.usage.UsageSource usageSource() {
                return com.kwiki.usage.UsageSource.EVAL;
            }
        };

        harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, evalObserver).collectList().block(Duration.ofSeconds(10));

        assertThat(seen).isNotEmpty().containsOnly(com.kwiki.usage.UsageSource.EVAL);
    }

    @Test
    void cancellationInsideANodeIsLoggedAsInfoNotAsAGraphError() {
        var graphLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.bsc.langgraph4j");
        var workflowLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow.class);
        var graphEvents = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var workflowEvents = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        graphEvents.start();
        workflowEvents.start();
        graphLogger.addAppender(graphEvents);
        workflowLogger.addAppender(workflowEvents);
        try {
            var harness = harness();
            harness.bm25.hits = List.of(hit("C1", "候选依据 C1"));
            harness.vector.hits = harness.bm25.hits;
            // 生成前取消：模拟用户在回答生成阶段手动中断
            RunObserver cancelBeforeGeneration = event -> {
                if (event instanceof RunObserver.ContextObserved) RunContext.current().close();
            };

            var stream = harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                    RouteInput.NONE, cancelBeforeGeneration).collectList().block(Duration.ofSeconds(10));

            assertThat(stream.getLast().type()).isEqualTo("error");
            assertThat(stream.getLast().payloadMap()).containsEntry("error", "cancelled");
            assertThat(graphEvents.list).noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR);
            assertThat(workflowEvents.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(event.getFormattedMessage()).contains("cancelled").contains("node=generate");
            });
        } finally {
            graphLogger.detachAppender(graphEvents);
            workflowLogger.detachAppender(workflowEvents);
        }
    }
}
