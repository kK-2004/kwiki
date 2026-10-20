package com.kwiki.eval;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowProbeTest {

    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());

    AgenticTestSupport.Harness harness() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class), versions,
                new com.kwiki.infrastructure.redis.ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        return AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
    }

    @Test
    void collectsAnswerOutcomeAndFinalContext() {
        var harness = harness();
        harness.bm25.hits = List.of(new ChunkHit("C1", "P1", 7, "PAGE", 1, 1L, "heading", 0, 12, "候选依据 C1 使用容器部署"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var result = new WorkflowProbe(harness.workflow).run(user, 7L, "什么是部署方式", Duration.ofSeconds(10));

        assertThat(result.outcome()).isEqualTo("completed");
        assertThat(result.errorCode()).isNull();
        assertThat(result.answer()).isEqualTo("基于证据的回答 [P0]");
        assertThat(result.context()).contains("候选依据 C1");
        assertThat(result.rewrites()).isEmpty();
    }

    @Test
    void recordsRewritesWhenQualityKeepsFailing() {
        var harness = harness();
        harness.bm25.hits = List.of(new ChunkHit("C1", "P1", 7, "PAGE", 1, 1L, "heading", 0, 6, "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.quality.passes = input -> false;

        var result = new WorkflowProbe(harness.workflow).run(user, 7L, "什么是部署方式", Duration.ofSeconds(10));

        assertThat(result.rewrites()).isNotEmpty();
        assertThat(result.outcome()).isEqualTo("insufficient");
    }
}
