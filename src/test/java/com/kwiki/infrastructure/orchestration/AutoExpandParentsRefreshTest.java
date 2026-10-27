package com.kwiki.infrastructure.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.orchestration.RouteInput;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
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
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** kwiki.agentic.auto-expand-parents 随 Nacos 推送热更新，非法值保留原值。 */
class AutoExpandParentsRefreshTest {
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());

    @Test
    void refreshTogglesParentExpansionAndRejectsInvalidValues() {
        var harness = harness();
        var workflow = (LangGraphAgenticWorkflow) harness.workflow;
        Map<String, Object> nacos = new HashMap<>(Map.of("kwiki.agentic.auto-expand-parents", "false"));
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("nacos", nacos));
        workflow.configureAutoExpandParents(env);
        assertThat(attemptStage(harness)).isEqualTo("BASE_CHILD");

        nacos.put("kwiki.agentic.auto-expand-parents", "true");
        workflow.onEnvironmentChange();
        assertThat(attemptStage(harness)).isEqualTo("BASE_PARENT");

        nacos.put("kwiki.agentic.auto-expand-parents", "sometimes");
        workflow.onEnvironmentChange();
        assertThat(attemptStage(harness)).as("非法值保留原值").isEqualTo("BASE_PARENT");

        nacos.put("kwiki.agentic.auto-expand-parents", "false");
        workflow.onEnvironmentChange();
        assertThat(attemptStage(harness)).isEqualTo("BASE_CHILD");

        nacos.remove("kwiki.agentic.auto-expand-parents");
        workflow.onEnvironmentChange();
        assertThat(attemptStage(harness)).as("删除配置回到默认开启").isEqualTo("BASE_PARENT");
    }

    String attemptStage(Harness harness) {
        var events = harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE).collectList().block(Duration.ofSeconds(10));
        return String.valueOf(events.getLast().payloadMap().get("attemptStage"));
    }

    Harness harness() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class), versions,
                new com.kwiki.infrastructure.redis.ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        var harness = AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
        var child = new ChunkHit("C1", "P1", 1, "PAGE", 11, 1L, "heading", 0, 6, "候选依据 C1");
        harness.bm25.hits = List.of(child);
        harness.vector.hits = List.of(child);
        harness.parents.byKey.put("P1", new ParentEvidenceChunk("P1", 1, "PAGE", 11, 1L, "heading",
                "完整父段落 P1", 0, List.of(child)));
        return harness;
    }
}
