package com.kwiki.endtoend;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.rag.answer.AgenticAnswerService;
import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.rag.testutil.AgenticTestSupportHarness;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * In-process end-to-end flow over the QA-gated state machine with scripted
 * leaves: corpus chunks → dual recall → RRF → candidate → gate → published
 * answer with child citations, plus access revocation during parent fetch
 * aborting the stream before any token leaves the server.
 */
class EndToEndRetrievalFlowTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);

    /** Fake corpus: chunks of kb 1, indexed from the published markdown pipeline. */
    private static final List<ChunkHit> CORPUS =
            List.of(
                    new ChunkHit(
                            "PAGE:7:3:P0:C0", "PAGE:7:3:P0", 1L, "PAGE", 7L, 3L,
                            "部署", 0, 60,
                            "kwiki 连接外部 MySQL、Redis、内容中心、Elasticsearch"),
                    new ChunkHit(
                            "PAGE:8:2:P0:C0", "PAGE:8:2:P0", 1L, "PAGE", 8L, 2L,
                            "权限", 0, 40,
                            "成员角色 OWNER EDITOR VIEWER 控制知识库访问"));

    private AgenticAnswerService service(
            AgenticTestSupportHarness.RecallScript bm25,
            AgenticTestSupportHarness.RecallScript vector,
            AgenticTestSupportHarness.ParentScript parents,
            java.util.function.Supplier<reactor.core.publisher.Flux<String>> llm,
            ScopeVersionService scopeVersions,
            AuthorizationScopeResolver scopes) {
        var metrics = new SimpleMeterRegistry();
        var workflow = AgenticTestSupportHarness.build(
                new RuleFirstRouter(KeywordRuleSet.defaults(),
                        StandardTestProperties.nullProvider(), false, metrics),
                scopes, scopeVersions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()),
                metrics, bm25, vector, parents, llm);
        return new AgenticAnswerService(workflow);
    }

    private static AuthorizationScopeResolver adminScopes(ScopeVersionService versions) {
        return new AuthorizationScopeResolver(
                Mockito.mock(KnowledgeBaseMemberRepository.class),
                versions,
                new ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(60)));
    }

    private static AgenticTestSupportHarness.RecallScript matchingBothBranches() {
        return (query, vec, filter, topK) ->
                CORPUS.stream()
                        .filter(hit -> query.contains("部署") ? hit.chunkKey().contains(":7:") : true)
                        .limit(topK)
                        .toList();
    }

    private static AgenticTestSupportHarness.ParentScript parentFetcher() {
        return (keys, filter) ->
                keys.stream()
                        .map(key -> new ParentEvidenceChunk(
                                key, 1L, "PAGE", 7L, 3L, "部署",
                                "# 部署\nkwiki 连接外部中间件，绝不创建容器。", 0.4,
                                CORPUS.stream()
                                        .filter(hit -> hit.parentChunkKey().equals(key))
                                        .toList()))
                        .toList();
    }

    @Test
    void markdownToSseCitationsFlowsEndToEnd() {
        var versions = new ScopeVersionService(StandardTestProperties.nullProvider());
        List<ChatStreamEvent> events =
                service(
                        matchingBothBranches(),
                        matchingBothBranches(),
                        parentFetcher(),
                        () -> {
                            return Flux.just("kwiki ", "连接外部中间件。[P0]");
                        },
                        versions,
                        adminScopes(versions))
                        .answer(ADMIN, "什么是 kwiki 的部署方式")
                        .collectList()
                        .block(Duration.ofSeconds(10));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        assertThat(types.get(0)).isIn("activity", "session");
        assertThat(types).contains("activity", "token", "citations");
        assertThat(types.get(types.size() - 1)).isEqualTo("done");
        assertThat(events.getLast().payloadMap().get("outcome")).isEqualTo("completed");

        ChatStreamEvent citations = events.get(types.indexOf("citations"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) citations.payloadMap().get("citations");
        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0)).containsEntry("childChunkKey", "PAGE:7:3:P0:C0");
    }

    @Test
    void accessRevocationDuringRetrievalAbortsTheStream() {
        CurrentUser member = new CurrentUser(7L, "member", false);
        KnowledgeBaseMemberRepository memberRepo =
                Mockito.mock(KnowledgeBaseMemberRepository.class);
        Mockito.when(memberRepo.findByUserId(7L))
                .thenReturn(List.of(new com.kwiki.wiki.domain.KnowledgeBaseMember(
                        1L, 7L, com.kwiki.wiki.access.KnowledgeBaseRole.VIEWER, 1L)));

        ScopeVersionService scopeVersions =
                new ScopeVersionService(StandardTestProperties.nullProvider());
        // scope resolves with version 1; revocation lands mid-flight during the
        // recall itself so every later authorization check fails closed
        AgenticTestSupportHarness.RecallScript revokingRecall =
                (query, vec, filter, topK) -> {
                    scopeVersions.bump(1L);
                    return matchingBothBranches().search(query, vec, filter, topK);
                };

        List<ChatStreamEvent> events =
                service(
                        revokingRecall,
                        matchingBothBranches(),
                        parentFetcher(),
                        () -> Flux.just("never used"),
                        scopeVersions,
                        new AuthorizationScopeResolver(
                                memberRepo, scopeVersions,
                                new ScopeCache(StandardTestProperties.nullProvider(),
                                        Duration.ofSeconds(60))))
                        .answer(member, "什么是 kwiki 的部署方式")
                        .collectList()
                        .block(Duration.ofSeconds(10));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        assertThat(types).doesNotContain("token", "citations");
        assertThat(types.get(types.size() - 1)).isEqualTo("error");
        assertThat(events.get(types.size() - 1).payloadMap().get("error"))
                .isEqualTo("authorization-changed");
    }
}
