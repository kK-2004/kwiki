package com.kwiki.rag.tool;

import static org.mockito.Mockito.mock;

import com.kwiki.graph.GraphSelectionScope;
import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.rewrite.ChatTurn;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工具执行器测试的共享夹具：真实的检索/父块服务 + 脚本化的召回分支、父块获取器与改写器。 */
final class AgentToolTestSupport {
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());
    final AuthorizationScopeResolver scopes;
    final AgenticTestSupport.Harness harness;

    AgentToolTestSupport() {
        var metrics = new SimpleMeterRegistry();
        scopes = new AuthorizationScopeResolver(
                mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class),
                versions,
                new com.kwiki.infrastructure.redis.ScopeCache(
                        StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(),
                StandardTestProperties.nullProvider(), false, metrics);
        harness = AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
    }

    RunContext run() {
        return run(AgenticLimits.defaults());
    }

    RunContext run(AgenticLimits limits) {
        return new RunContext("r", scopes.resolve(user, Set.of(), Set.of()), versions::current, limits, Map.of());
    }

    ToolExecutionContext context(RunContext run, EvidencePool pool) {
        return context(run, pool, List.of());
    }

    ToolExecutionContext context(RunContext run, EvidencePool pool, List<ChatTurn> history) {
        return new ToolExecutionContext(run, pool, "原始问题", history, GraphSelectionScope.all(), 1);
    }

    static ChunkHit hit(String key, String parent, String content) {
        return new ChunkHit(key, parent, 1, "PAGE", 1, 1L, "heading", 0, content.length(), content);
    }

    static ChildEvidence child(String key, String parent, String content, double score) {
        return ChildEvidence.fromHit(hit(key, parent, content), 1, 1, score);
    }
}
