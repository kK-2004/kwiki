package com.kwiki.rag.orchestration;

import static com.kwiki.testutil.AgenticTestSupport.ScriptPlanner.call;
import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.CandidateAnswer;
import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
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

/** plan-execute：脚本化 planner + 真实工具执行器 + 真实状态机与质量门禁。 */
class AgenticPlannerWorkflowTest {
    static final String SEARCH = "{\"queries\":[\"部署\"],\"strategy\":\"HYBRID\",\"range\":\"BASE\"}";
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());
    Harness harness;
    ScriptPlanner planner;

    void setUp() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class), versions,
                new com.kwiki.infrastructure.redis.ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        harness = AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
        planner = harness.enablePlanner();
    }

    List<ChatStreamEvent> run(String query) {
        return run(query, RouteInput.NONE);
    }

    List<ChatStreamEvent> run(String query, RouteInput input) {
        return harness.workflow.answerInSession(user, query, List.of(), Set.of(), Set.of(), input)
                .collectList().block(Duration.ofSeconds(10));
    }

    static ChunkHit hit(String key, String parent, long kb, long resource) {
        return new ChunkHit(key, parent, kb, "PAGE", resource, 1L, "heading", 0, 6, "候选依据 " + key);
    }

    void corpus(ChunkHit... hits) {
        harness.bm25.hits = List.of(hits);
        harness.vector.hits = List.of(hits);
    }

    void stubParent(String parentKey, ChunkHit child) {
        // 父块必须与子块同源（kbId / resourceId / revisionId / resourceType 一致），否则解析器会丢弃该子块
        harness.parents.byKey.put(parentKey, new ParentEvidenceChunk(parentKey, child.kbId(), child.resourceType(),
                child.resourceId(), child.revisionId(), "heading", "完整父段落 " + parentKey, 0, List.of(child)));
    }

    /** 默认跟随生产配置开启自动补父块；验证显式 expand_parents 的用例关闭它。 */
    void disableAutoExpandParents() {
        ((com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow) harness.workflow).useAutoExpandParents(false);
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
    void oneRoundPlanExecuteGenerateAndPublish() {
        setUp();
        disableAutoExpandParents();
        corpus(hit("C1", "P1", 1, 11), hit("C2", "P2", 1, 12));
        harness.answer.scripted.add("基于证据的回答 [P0]");
        planner.then(call("a", "search_chunks", SEARCH)); // 续规划时队列已空 → 空计划 → 直接生成

        var events = run("什么是部署方式");

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed")
                .containsEntry("attemptStage", "BASE_CHILD");
        assertThat(tokens(events)).isEqualTo("基于证据的回答 [P0]");
        assertThat(planner.requests).as("检索后回 planner 一次，决定是否补父块").hasSize(2);
        assertThat(toolNames(planner.requests.getFirst())).containsExactly("search_chunks", "rewrite_query");
        var replan = planner.requests.get(1);
        assertThat(toolNames(replan)).contains("expand_parents");
        assertThat(replan.observation()).as("续规划不占查询轮次").containsEntry("round", 1)
                .containsEntry("evidenceUnexamined", true);
        assertThat(harness.bm25.topKs).as("只有工具这一次检索，没有走固定阶梯").containsExactly(20);
        assertThat(harness.parents.requestedKeys).isEmpty();
        var phases = events.stream().filter(e -> e.type().equals("activity"))
                .map(e -> String.valueOf(e.payloadMap().get("phase"))).toList();
        assertThat(phases).contains("ROUTE", "PLAN", "RETRIEVAL", "GENERATION", "QUALITY");
        assertThat(route(done(events))).containsEntry("intent", "KNOWLEDGE_QA");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchChunksActivityCarriesTheNewlyAddedChunksAsSources() {
        setUp();
        corpus(hit("C1", "P1", 1, 11), hit("C2", "P2", 1, 12));
        harness.answer.scripted.add("基于证据的回答 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        var events = run("什么是部署方式");

        var finished = events.stream().filter(e -> e.type().equals("activity"))
                .map(ChatStreamEvent::payloadMap)
                .filter(p -> "RETRIEVAL".equals(p.get("phase")) && "COMPLETED".equals(p.get("status")))
                .findFirst().orElseThrow();
        var metrics = (Map<String, Object>) finished.get("metrics");
        assertThat(metrics).containsEntry("addedCount", 2);
        var sources = (List<Map<String, Object>>) metrics.get("sources");
        assertThat(sources).extracting(source -> source.get("childChunkKey")).containsExactlyInAnyOrder("C1", "C2");
        assertThat(sources.getFirst()).containsKeys("parentChunkKey", "kbId", "resourceId", "resourceType",
                "revisionId", "headingPath", "charStart", "charEnd", "excerpt");
    }

    @Test
    void failedReviewReplansWithTheReviewAndExpandsToParents() {
        setUp();
        disableAutoExpandParents();
        var child = hit("C1", "P1", 1, 11);
        corpus(child);
        stubParent("P1", child);
        harness.quality.passes = input -> input.candidate().evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT;
        harness.answer.scripted.add("子候选 [P0]");
        harness.answer.scripted.add("父候选完整回答 [P0]");
        // 检索后的续规划先不补父块（空计划），评审不过后的下一轮再 expand_parents
        planner.then(call("a", "search_chunks", SEARCH)).then().then(call("b", "expand_parents", "{}"));

        var events = run("什么是部署方式");

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed")
                .containsEntry("attemptStage", "BASE_PARENT");
        assertThat(tokens(events)).isEqualTo("父候选完整回答 [P0]");
        assertThat(planner.requests).hasSize(3);
        var second = planner.requests.get(2);
        assertThat(toolNames(second)).contains("expand_parents", "rewrite_query");
        @SuppressWarnings("unchecked")
        var review = (Map<String, Object>) second.observation().get("lastReview");
        assertThat(review).containsEntry("reasonCode", "coverage-missing");
        assertThat(second.history()).as("上一轮的工具调用与结果被带回").hasSize(1);
        assertThat(harness.answer.prompts).hasSize(2);
        assertThat(harness.answer.prompts.get(1)).contains("完整父段落 P1");
    }

    @Test
    void invalidPlansDegradeToTheFixedLadderAndStillAnswer() {
        setUp();
        corpus(hit("C1", "P1", 1, 11));
        harness.answer.scripted.add("阶梯上的回答 [P0]");
        planner.then(call("a", "delete_everything", "{}")).then(call("b", "delete_everything", "{}"));

        var events = run("什么是部署方式");

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed");
        assertThat(tokens(events)).isEqualTo("阶梯上的回答 [P0]");
        assertThat(planner.requests).hasSize(2);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("unknown-tool");
        assertThat(harness.bm25.topKs).as("降级后由固定阶梯检索").containsExactly(20);
        assertThat(metrics.counter("kwiki_planner_degraded_total", "reason", "planner-invalid-output").count())
                .isEqualTo(1.0);
    }

    @Test
    void providerFailureDegradesImmediately() {
        setUp();
        corpus(hit("C1", "P1", 1, 11));
        harness.answer.scripted.add("阶梯上的回答 [P0]");
        planner.scripted.add(new IllegalStateException("provider down"));

        var events = run("什么是部署方式");

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed");
        assertThat(planner.requests).hasSize(1);
    }

    @Test
    void emptyPlansWithoutEvidenceDegrade() {
        setUp();
        corpus(hit("C1", "P1", 1, 11));
        harness.answer.scripted.add("阶梯上的回答 [P0]");

        var events = run("什么是部署方式"); // 队列为空 → planner 两次都返回空计划

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed");
        assertThat(planner.requests).hasSize(2);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("no-tool-calls-without-new-evidence");
    }

    @Test
    void abortsFromThePlannerEndTheRunInsteadOfDegrading() {
        setUp();
        planner.scripted.add(new RunFailure("timeout"));

        var events = run("什么是部署方式");

        assertThat(done(events).type()).isEqualTo("error");
        assertThat(done(events).payloadMap()).containsEntry("error", "timeout");
        assertThat(harness.bm25.queries).isEmpty();
    }

    @Test
    void roundsAreBoundedAndUnchangedEvidenceIsNotRegenerated() {
        setUp();
        corpus(hit("C1", "P1", 1, 11));
        harness.quality.passes = input -> false;
        harness.answer.scripted.add("总是不过的候选 [P0]");
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "search_chunks", SEARCH))
                .then(call("c", "search_chunks", SEARCH));

        var events = run("什么是部署方式");

        assertThat(planner.requests).hasSize(3);
        assertThat(harness.answer.prompts).as("证据没变就不重新生成").hasSize(1);
        assertThat(done(events).payloadMap()).containsEntry("outcome", AgenticErrorCodes.INSUFFICIENT);
    }

    @Test
    void plannerAsksTheUserWithOptionsDerivedFromTheEvidence() {
        setUp();
        corpus(hit("C1", "P1", 1, 11), hit("C2", "P2", 2, 22));
        harness.quality.passes = input -> false;
        harness.answer.scripted.add("不会发布的候选 [P0]");
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "ask_user",
                "{\"question\":\"你想了解哪个知识库？\",\"options\":[{\"ref\":\"C1\",\"label\":\"部署手册\"},"
                        + "{\"ref\":\"C2\",\"label\":\"运维手册\"}]}"));

        var events = run("什么是部署方式");

        var clarification = events.stream().filter(e -> e.type().equals("clarification")).findFirst().orElseThrow();
        var payload = clarification.payloadMap();
        assertThat(payload).containsEntry("source", "planner").containsEntry("question", "你想了解哪个知识库？");
        @SuppressWarnings("unchecked")
        var options = (List<Map<String, Object>>) payload.get("options");
        assertThat(options).extracting(o -> o.get("kbId")).containsExactly(1, 2);
        assertThat(options).extracting(o -> o.get("pageId")).containsExactly(11, 22);
        assertThat(done(events).payloadMap()).containsEntry("outcome", "clarification")
                .containsEntry("clarificationId", payload.get("clarificationId"));
        assertThat(harness.answer.prompts).as("反问之后不再生成").hasSize(1);
        assertThat(toolNames(planner.requests.get(1))).contains("ask_user");
        assertThat(toolNames(planner.requests.getFirst())).doesNotContain("ask_user");
    }

    @Test
    void askUserIsNotOfferedToAClarificationResume() {
        setUp();
        corpus(hit("C1", "P1", 1, 11));
        harness.quality.passes = input -> false;
        harness.answer.scripted.add("候选 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        run("什么是部署方式", new RouteInput(null, true));

        assertThat(planner.requests.size()).isGreaterThanOrEqualTo(2);
        assertThat(planner.requests.stream().map(AgenticPlannerWorkflowTest::toolNames).flatMap(List::stream))
                .doesNotContain("ask_user");
    }

    @Test
    void askUserBatchedWithOtherToolsIsRetriedWithTheReason() {
        setUp();
        corpus(hit("C1", "P1", 1, 11));
        harness.quality.passes = input -> false;
        harness.answer.scripted.add("候选 [P0]");
        String ask = "{\"question\":\"哪一个？\",\"options\":[{\"ref\":\"C1\",\"label\":\"甲\"}]}";
        planner.then(call("a", "search_chunks", SEARCH))
                .then(call("b", "ask_user", ask), call("c", "rewrite_query", "{\"mode\":\"EXPANSION\"}"))
                .then(call("d", "ask_user", ask));

        var events = run("什么是部署方式");

        assertThat(planner.requests.get(2).validationError()).isEqualTo("ask_user_must_be_alone");
        assertThat(done(events).payloadMap()).containsEntry("outcome", "clarification");
    }

    @Test
    void disabledPlannerKeepsTheFixedLadder() {
        setUp();
        var engine = (com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow) harness.workflow;
        engine.usePlanner(null, null, false);
        corpus(hit("C1", "P1", 1, 11));
        harness.answer.scripted.add("阶梯上的回答 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        var events = run("什么是部署方式");

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed");
        assertThat(planner.requests).isEmpty();
    }
}
