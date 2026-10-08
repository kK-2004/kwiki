package com.kwiki.rag.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.answer.DirectAnswerPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.rewrite.ChatTurn;
import com.kwiki.rag.routing.Intent;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.PriorRoute;
import com.kwiki.rag.routing.RouteSource;
import com.kwiki.rag.routing.RouterLlmPort;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.AgenticTestSupport.Harness;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 路由真正分流：问候、直答、直答升级、反问与续跑；脚本化叶子适配器，状态机真实运行。 */
class AgenticRoutingWorkflowTest {

    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());

    Harness harness(RouterLlmPort llm) {
        ObjectProvider<RouterLlmPort> provider = new ObjectProvider<>() {
            @Override
            public RouterLlmPort getIfAvailable() {
                return llm;
            }
        };
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), provider, llm != null, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class),
                versions,
                new com.kwiki.infrastructure.redis.ScopeCache(
                        StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        return AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
    }

    List<ChatStreamEvent> run(Harness harness, String query, List<ChatTurn> history, RouteInput input) {
        return harness.workflow.answerInSession(user, query, history, Set.of(), Set.of(), input)
                .collectList().block(Duration.ofSeconds(10));
    }

    static String tokens(List<ChatStreamEvent> events) {
        return events.stream().filter(event -> event.type().equals("token"))
                .map(event -> String.valueOf(event.payloadMap().get("text")))
                .collect(Collectors.joining());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> route(ChatStreamEvent done) {
        return (Map<String, Object>) done.payloadMap().get("route");
    }

    static ChatStreamEvent event(List<ChatStreamEvent> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
    }

    static void corpus(Harness harness) {
        var hit = new ChunkHit("C1", "P1", 1, "PAGE", 1, 1L, "heading", 0, 8, "候选依据 [P0]");
        harness.bm25.hits = List.of(hit);
        harness.vector.hits = List.of(hit);
    }

    static Map<String, Object> decision(boolean needsRetrieval, double confidence, Map<String, Object> clarify) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", needsRetrieval ? "KNOWLEDGE_QA" : "DIRECT_ANSWER");
        m.put("needsRetrieval", needsRetrieval);
        m.put("rewriteMode", "NONE");
        m.put("subqueries", List.of());
        m.put("confidence", confidence);
        if (clarify != null) {
            m.put("clarify", clarify);
        }
        return m;
    }

    @Test
    void greetingGetsTheFixedReplyWithoutRetrievalOrDirectModel() {
        var harness = harness(null);

        var events = run(harness, "你好！", List.of(), RouteInput.NONE);

        assertThat(tokens(events)).contains("你好，我可以根据你有权访问的 Wiki 内容");
        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "direct");
        assertThat(harness.bm25.queries).isEmpty();
        assertThat(harness.direct.questions).isEmpty();
    }

    @Test
    void routerSaysNoRetrievalSoDirectNodeAnswers() {
        var harness = harness((q, t) -> Optional.of(decision(false, 0.9, null)));
        harness.direct.scripted.add("Hello, world.");

        var events = run(harness, "帮我把这句话翻译成英文", List.of(), RouteInput.NONE);

        assertThat(tokens(events)).isEqualTo("Hello, world.");
        assertThat(harness.bm25.queries).isEmpty();
        assertThat(harness.answer.prompts).isEmpty();
        var done = events.getLast();
        assertThat(done.type()).isEqualTo("done");
        assertThat(done.payloadMap()).containsEntry("outcome", "direct");
        assertThat(route(done)).containsEntry("needsRetrieval", false);
    }

    @Test
    void directSentinelEscalatesToRetrievalExactlyOnce() {
        var harness = harness((q, t) -> Optional.of(decision(false, 0.9, null)));
        harness.direct.scripted.add(DirectAnswerPort.ESCALATE_SENTINEL);
        corpus(harness);
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var events = run(harness, "帮我看看报销流程", List.of(), RouteInput.NONE);

        assertThat(harness.direct.questions).hasSize(1);
        assertThat(harness.bm25.queries).isNotEmpty();
        assertThat(tokens(events)).isEqualTo("基于证据的回答 [P0]");
        var done = events.getLast();
        assertThat(done.payloadMap()).containsEntry("outcome", "completed");
        assertThat(route(done)).containsEntry("needsRetrieval", true);
    }

    @Test
    void unavailableDirectModelFallsThroughToRetrieval() {
        var harness = harness((q, t) -> Optional.of(decision(false, 0.9, null)));
        harness.direct.fail = true;
        corpus(harness);
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var events = run(harness, "帮我看看报销流程", List.of(), RouteInput.NONE);

        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed");
        assertThat(harness.bm25.queries).isNotEmpty();
    }

    @Test
    void anaphoraWithoutHistoryEmitsClarificationAndEndsTheRun() {
        var harness = harness(null);

        var events = run(harness, "它怎么用？", List.of(), RouteInput.NONE);

        var clarification = event(events, "clarification").payloadMap();
        assertThat(clarification).containsKeys("clarificationId", "question", "options", "allowFreeText")
                .containsEntry("source", "router")
                .containsEntry("allowFreeText", true);
        var done = events.getLast();
        assertThat(done.type()).isEqualTo("done");
        assertThat(done.payloadMap()).containsEntry("outcome", "clarification")
                .containsEntry("clarificationId", clarification.get("clarificationId"));
        assertThat(tokens(events)).isEmpty();
        assertThat(harness.bm25.queries).isEmpty();
        assertThat(events.stream().map(ChatStreamEvent::type))
                .containsSubsequence("clarification", "done");
    }

    @Test
    void anaphoraWithHistoryAndNoLlmFallsBackToRetrieval() {
        var harness = harness(null);
        corpus(harness);
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var events = run(harness, "它怎么用？",
                List.of(new ChatTurn("USER", "介绍一下知识库")),
                new RouteInput(new PriorRoute(Intent.KNOWLEDGE_QA, RouteSource.RULE, true, 1.0), false));

        assertThat(events.stream().map(ChatStreamEvent::type)).doesNotContain("clarification");
        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "completed");
    }

    @Test
    void llmClarifyCarriesOptionsAndResumedRunSkipsIt() {
        Map<String, Object> clarify = Map.of(
                "question", "你想了解哪一个？",
                "options", List.of(Map.of("id", "a", "label", "部署"), Map.of("id", "b", "label", "运维")));
        var harness = harness((q, t) -> Optional.of(decision(true, 0.9, clarify)));

        var asked = run(harness, "随便聊聊公司最近的动静", List.of(), RouteInput.NONE);
        var payload = event(asked, "clarification").payloadMap();
        assertThat((List<?>) payload.get("options")).hasSize(2);
        assertThat(asked.getLast().payloadMap()).containsEntry("outcome", "clarification");

        corpus(harness);
        harness.answer.scripted.add("基于证据的回答 [P0]");
        var resumed = run(harness, "随便聊聊公司最近的动静\n（用户补充：部署）", List.of(),
                new RouteInput(null, true));
        assertThat(resumed.stream().map(ChatStreamEvent::type)).doesNotContain("clarification");
        assertThat(resumed.getLast().payloadMap()).containsEntry("outcome", "completed");
    }

    @Test
    void doneEventCarriesTheFinalRouteStateForPersistence() {
        var harness = harness(null);
        corpus(harness);
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var events = run(harness, "什么是部署方式", List.of(), RouteInput.NONE);

        assertThat(route(events.getLast())).containsEntry("intent", "KNOWLEDGE_QA")
                .containsEntry("source", "RULE")
                .containsEntry("needsRetrieval", true);
    }
}
