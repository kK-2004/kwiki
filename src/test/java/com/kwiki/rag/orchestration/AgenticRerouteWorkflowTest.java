package com.kwiki.rag.orchestration;

import static com.kwiki.testutil.AgenticTestSupport.ScriptPlanner.call;
import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.AgenticTestSupport.Harness;
import com.kwiki.testutil.AgenticTestSupport.ScriptPlanner;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 意图修正：planner 的 answer_directly 改道，以及固定阶梯在证据无关时跳过 *_PARENT。 */
class AgenticRerouteWorkflowTest {
    static final String SEARCH = "{\"queries\":[\"云朵\"],\"strategy\":\"HYBRID\",\"range\":\"BASE\"}";
    static final String DIRECT = "{\"reason\":\"与知识库无关\"}";
    /** 规则未命中，路由兜底成检索。 */
    static final String QUERY = "窗外云朵的颜色";
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());
    Harness harness;
    ScriptPlanner planner;

    void setUp(boolean withPlanner) {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class), versions,
                new com.kwiki.infrastructure.redis.ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        harness = AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
        planner = withPlanner ? harness.enablePlanner() : null;
    }

    List<ChatStreamEvent> run(String query, RouteInput input) {
        return harness.workflow.answerInSession(user, query, List.of(), Set.of(), Set.of(), input)
                .collectList().block(Duration.ofSeconds(10));
    }

    static ChunkHit hit(String key, String parent) {
        return new ChunkHit(key, parent, 1, "PAGE", 11, 1L, "heading", 0, 6, "候选依据 " + key);
    }

    void corpus(ChunkHit... hits) {
        harness.bm25.hits = List.of(hits);
        harness.vector.hits = List.of(hits);
    }

    static ChatStreamEvent done(List<ChatStreamEvent> events) {
        return events.getLast();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> route(ChatStreamEvent done) {
        return (Map<String, Object>) done.payloadMap().get("route");
    }

    static String tokens(List<ChatStreamEvent> events) {
        return events.stream().filter(e -> e.type().equals("token"))
                .map(e -> String.valueOf(e.payloadMap().get("text"))).collect(Collectors.joining());
    }

    static List<String> toolNames(com.kwiki.rag.tool.AgentPlannerPort.Request request) {
        return request.tools().stream().map(ToolRegistry.ToolDefinition::name).toList();
    }

    @Test
    void plannerReroutesToDirectAfterAnIrrelevantReview() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        harness.direct.scripted.add("我没有实时时钟，无法告知窗外云朵的颜色。");
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", "direct");
        assertThat(tokens(events)).isEqualTo("我没有实时时钟，无法告知窗外云朵的颜色。");
        assertThat(planner.requests).hasSize(2);
        assertThat(toolNames(planner.requests.getFirst())).as("第一轮还没有评审，不暴露").doesNotContain("answer_directly");
        assertThat(toolNames(planner.requests.get(1))).endsWith("answer_directly");
        assertThat(harness.direct.questions).containsExactly(QUERY);
        assertThat(harness.bm25.topKs).as("改道后不再检索").hasSize(1);
        assertThat(route(done(events))).containsEntry("intent", "DIRECT_ANSWER").containsEntry("source", "PLANNER")
                .containsEntry("needsRetrieval", false);
    }

    @Test
    void rerouteIsNotOfferedWhenTheReviewShowsRelevantEvidence() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.passes = input -> false; // 覆盖不足但相关（relevance 0.95）
        harness.answer.scripted.add("候选 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        run(QUERY, RouteInput.NONE);

        assertThat(planner.requests.size()).isGreaterThanOrEqualTo(2);
        for (var request : planner.requests) {
            assertThat(toolNames(request)).doesNotContain("answer_directly");
        }
    }

    @Test
    void rerouteIsNotOfferedToAClarificationResume() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        run(QUERY, new RouteInput(null, true));

        assertThat(planner.requests.size()).isGreaterThanOrEqualTo(2);
        for (var request : planner.requests) {
            assertThat(toolNames(request)).doesNotContain("answer_directly");
        }
    }

    @Test
    void directAfterRerouteNeverEscalatesBackToRetrieval() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        harness.direct.scripted.add("[[NEEDS_KNOWLEDGE]]");
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", AgenticErrorCodes.INSUFFICIENT);
        assertThat(harness.bm25.topKs).as("没有再次检索").hasSize(1);
        assertThat(planner.requests).hasSize(2);
    }

    @Test
    void directFailureAfterRerouteEndsInsufficientInsteadOfRetrieving() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        harness.direct.fail = true;
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", AgenticErrorCodes.INSUFFICIENT);
        assertThat(harness.bm25.topKs).hasSize(1);
    }

    @Test
    void fixedLadderSkipsParentStagesWhenTheEvidenceIsIrrelevant() {
        setUp(false);
        corpus(hit("C1", "P1"));
        // 只有扩大后的子块阶段通过；基础子块阶段评审为「证据无关」
        harness.quality.irrelevant = input -> input.candidate().attemptStage() == AttemptStage.BASE_CHILD;
        harness.quality.passes = input -> input.candidate().attemptStage() == AttemptStage.EXPANDED_CHILD;
        harness.answer.scripted.add("基础子候选");
        harness.answer.scripted.add("扩大检索候选 [P0]");

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed")
                .containsEntry("attemptStage", "EXPANDED_CHILD");
        assertThat(harness.bm25.topKs).containsExactly(20, 50);
        assertThat(harness.parents.requestedKeys).as("跳过了 BASE_PARENT").isEmpty();
        assertThat(harness.answer.prompts).hasSize(2);
    }

    @Test
    void fixedLadderStillTriesParentsWhenTheEvidenceIsRelevantButIncomplete() {
        setUp(false);
        var child = hit("C1", "P1");
        corpus(child);
        harness.parents.byKey.put("P1", new com.kwiki.rag.retrieval.ParentEvidenceChunk("P1", child.kbId(),
                child.resourceType(), child.resourceId(), child.revisionId(), "heading", "完整父段落 P1", 0,
                List.of(child)));
        harness.quality.passes = input -> input.candidate().attemptStage() == AttemptStage.BASE_PARENT;
        harness.answer.scripted.add("基础子候选");
        harness.answer.scripted.add("父候选 [P0]");

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("attemptStage", "BASE_PARENT");
        assertThat(harness.parents.requestedKeys).isNotEmpty();
    }

    @Test
    void rerouteIsLoggedAtDebugLevelWithItsEvidence() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("kwiki.agentic.debug");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            setUp(true);
            corpus(hit("C1", "P1"));
            harness.quality.irrelevant = input -> true;
            harness.answer.scripted.add("无关的候选 [P0]");
            planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

            run(QUERY, RouteInput.NONE);

            String logs = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(logs).contains("\"status\":\"reroute\"", "\"fromIntent\":\"KNOWLEDGE_QA\"",
                    "\"toIntent\":\"DIRECT_ANSWER\"", "\"relevance\":0.0", "与知识库无关");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
