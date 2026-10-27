package com.kwiki.rag.orchestration;

import static com.kwiki.testutil.AgenticTestSupport.ScriptPlanner.call;
import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow;
import com.kwiki.rag.answer.CandidateAnswer;
import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.QaParentFetchService;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 生成前自动补父块：首次生成即用去重父块，未被父块覆盖的子块以截断父块补进上下文。 */
class AgenticAutoParentExpansionTest {
    static final String SEARCH = "{\"queries\":[\"部署\"],\"strategy\":\"HYBRID\",\"range\":\"BASE\"}";
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());

    Harness harness() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class), versions,
                new com.kwiki.infrastructure.redis.ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        var harness = AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
        ((LangGraphAgenticWorkflow) harness.workflow).useAutoExpandParents(true);
        return harness;
    }

    static ChunkHit hit(String key, String parent, long resource, int start, String content) {
        return new ChunkHit(key, parent, 1, "PAGE", resource, 1L, "heading", start, start + content.length(), content);
    }

    static void corpus(Harness harness, ChunkHit... hits) {
        harness.bm25.hits = List.of(hits);
        harness.vector.hits = List.of(hits);
    }

    static void stubParent(Harness harness, String parentKey, ChunkHit child, String body) {
        harness.parents.byKey.put(parentKey, new ParentEvidenceChunk(parentKey, child.kbId(), child.resourceType(),
                child.resourceId(), child.revisionId(), "heading", body, 0, List.of(child)));
    }

    List<ChatStreamEvent> run(Harness harness, String query) {
        return harness.workflow.answerInSession(user, query, List.of(), Set.of(), Set.of(), RouteInput.NONE)
                .collectList().block(Duration.ofSeconds(10));
    }

    static String tokens(List<ChatStreamEvent> events) {
        return events.stream().filter(e -> e.type().equals("token"))
                .map(e -> String.valueOf(e.payloadMap().get("text"))).collect(Collectors.joining());
    }

    @Test
    void fixedLadderGeneratesFromParentsOnTheFirstAttempt() {
        var harness = harness();
        var child = hit("C1", "P1", 11, 0, "姓名：元某某");
        corpus(harness, child);
        stubParent(harness, "P1", child, "姓名：元某某\n初步诊断：白内障");
        harness.answer.scripted.add("元某某的初步诊断为白内障 [P0]");

        var events = run(harness, "元某某的初步诊断是什么");

        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed")
                .containsEntry("attemptStage", "BASE_PARENT");
        assertThat(harness.answer.prompts).as("只生成一次，不先用子块试").hasSize(1);
        assertThat(harness.answer.prompts.getFirst()).contains("初步诊断：白内障");
        assertThat(harness.quality.inputs.getFirst().candidate().evidenceLevel())
                .isEqualTo(CandidateAnswer.EvidenceLevel.PARENT);
        assertThat(harness.parents.requestedKeys).containsExactly(List.of("P1"));
        assertThat(tokens(events)).isEqualTo("元某某的初步诊断为白内障 [P0]");
    }

    @Test
    void childrenWithoutAFetchedParentStayInTheContext() {
        var harness = harness();
        var covered = hit("C1", "P1", 11, 0, "甲公司营业收入");
        var orphan = hit("C2", "P2", 22, 0, "乙公司营业收入为1000万元");
        corpus(harness, covered, orphan);
        stubParent(harness, "P1", covered, "甲公司营业收入为31亿元");
        harness.answer.scripted.add("乙公司为1000万元 [P1]");

        var events = run(harness, "营业收入是多少");

        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed");
        assertThat(harness.answer.prompts.getFirst())
                .contains("[P0] 甲公司营业收入为31亿元", "[P1] 乙公司营业收入为1000万元");
        @SuppressWarnings("unchecked")
        var citations = (List<Map<String, Object>>) events.stream().filter(e -> e.type().equals("citations"))
                .findFirst().orElseThrow().payloadMap().get("citations");
        assertThat(citations).singleElement().satisfies(citation -> assertThat(citation)
                .containsEntry("citationId", "P1").containsEntry("childChunkKey", "C2")
                .containsEntry("parentChunkKey", "P2"));
    }

    @Test
    void failingReviewsTakeOneParentAttemptPerStageWithinTheFetchBudget() {
        var harness = harness();
        corpus(harness, hit("C1", "P1", 11, 0, "片段"));
        harness.quality.passes = input -> false;

        var events = run(harness, "什么是部署方式");

        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "insufficient");
        // 每轮 base + expanded 各一次父块级生成，不再有单独的子块级生成
        assertThat(harness.bm25.topKs).containsExactly(20, 50, 20, 50, 20, 50);
        assertThat(harness.answer.prompts).hasSize(6);
        assertThat(harness.quality.inputs).allSatisfy(input -> assertThat(input.candidate().evidenceLevel())
                .isEqualTo(CandidateAnswer.EvidenceLevel.PARENT));
        assertThat(harness.parents.requestedKeys).hasSize(AgenticLimits.defaults().parentFetches());
    }

    @Test
    void plannerExpandsParentsBeforeGeneratingWithoutAnExtraPlanningTurn() {
        var harness = harness();
        var planner = harness.enablePlanner();
        var child = hit("C1", "P1", 11, 0, "候选依据 C1");
        corpus(harness, child);
        stubParent(harness, "P1", child, "完整父段落 P1");
        harness.quality.passes = input -> input.candidate().evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT;
        harness.answer.scripted.add("父候选完整回答 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        var events = run(harness, "什么是部署方式");

        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed")
                .containsEntry("attemptStage", "BASE_PARENT");
        assertThat(planner.requests).hasSize(1);
        assertThat(harness.answer.prompts).singleElement().asString().contains("完整父段落 P1");
        assertThat(harness.parents.requestedKeys).containsExactly(List.of("P1"));
        assertThat(events.stream().filter(e -> e.type().equals("activity"))
                .map(e -> String.valueOf(e.payloadMap().get("phase"))))
                .contains("RETRIEVAL", "PARENT_FETCH", "GENERATION");
    }

    @Test
    void withoutAutoExpansionThePlannerIsAskedRightAfterSearchingAndCanExpandParents() {
        var harness = harness();
        var planner = harness.enablePlanner();
        ((LangGraphAgenticWorkflow) harness.workflow).useAutoExpandParents(false);
        var child = hit("C1", "P1", 11, 0, "候选依据 C1");
        corpus(harness, child);
        stubParent(harness, "P1", child, "完整父段落 P1");
        harness.answer.scripted.add("父候选完整回答 [P0]");
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "expand_parents", "{}"));

        var events = run(harness, "什么是部署方式");

        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed")
                .containsEntry("attemptStage", "BASE_PARENT");
        assertThat(planner.requests).hasSize(2);
        assertThat(planner.requests.get(1).observation()).containsEntry("round", 1);
        assertThat(harness.answer.prompts).as("补完父块才生成，不先用子块试")
                .singleElement().asString().contains("完整父段落 P1");
        var stepIds = events.stream().filter(e -> e.type().equals("activity"))
                .map(e -> String.valueOf(e.payloadMap().get("stepId"))).distinct().toList();
        assertThat(stepIds).contains("q1-base_child-plan", "q1-base_child-replan",
                "q1-base_child-tool-1-search_chunks", "q1-base_child-tool-2-expand_parents");
    }

    @Test
    void uncoveredChildrenAreGroupedPerParentInTextOrderAfterTheParents() {
        var parent = new ParentEvidence("P1", "PAGE", 11, 1L, 1, "heading", "正文 A 正文", false, 0,
                List.of());
        var inParent = child("C1", "P1", 11, 3, "A");
        var cutOff = child("C2", "P1", 11, 40, "被截掉的部分");
        var laterInText = child("C3", "P2", 22, 50, "后段");
        var earlierInText = child("C4", "P2", 22, 10, "前段");

        var merged = QaParentFetchService.withUncoveredChildren(List.of(parent),
                List.of(inParent, cutOff, laterInText, earlierInText));

        assertThat(merged).extracting(ParentEvidence::parentChunkKey).containsExactly("P1", "P1", "P2");
        assertThat(merged).extracting(ParentEvidence::firstHitOrder).containsExactly(0, 1, 2);
        assertThat(merged.get(1).body()).isEqualTo("被截掉的部分");
        assertThat(merged.get(2).body()).isEqualTo("前段\n……\n后段");
        assertThat(merged.get(2).truncated()).isTrue();
        assertThat(merged.get(2).matchedChildren()).extracting(ChunkHit::chunkKey).containsExactly("C3", "C4");
        assertThat(QaParentFetchService.withUncoveredChildren(List.of(parent), List.of(inParent)))
                .containsExactly(parent);
    }

    static ChildEvidence child(String key, String parent, long resource, int start, String content) {
        return new ChildEvidence(key, parent, 1, "PAGE", resource, 1L, "heading", start,
                start + content.length(), content, 1, 1, 0.5);
    }
}
