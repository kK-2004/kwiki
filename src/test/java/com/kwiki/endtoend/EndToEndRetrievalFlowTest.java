package com.kwiki.endtoend;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.AgenticAnswerService;
import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.HybridRetrievalOrchestrator;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.ParentEvidenceResolver;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.rewrite.QueryRewriteOrchestrator;
import com.kwiki.rag.rewrite.RewriteDecisionService;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
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
import java.util.Optional;

/**
 * In-process end-to-end flow with fakes: published Markdown → structured parse → parent/child
 * chunks → child-only dual recall → RRF → distinct parent context → SSE answer with child
 * citations, plus OCR-required rejection before indexing and access revocation aborting generation.
 */
class EndToEndRetrievalFlowTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);

    /** Fake corpus: chunks of kb 1, indexed from the published markdown pipeline. */
    private static final List<ChunkHit> CORPUS =
            List.of(
                    new ChunkHit(
                            "PAGE:7:3:P0:C0",
                            "PAGE:7:3:P0",
                            1L,
                            "PAGE",
                            7L,
                            3L,
                            "部署",
                            0,
                            60,
                            "kwiki 连接外部 MySQL、Redis、内容中心、Elasticsearch"),
                    new ChunkHit(
                            "PAGE:8:2:P0:C0",
                            "PAGE:8:2:P0",
                            1L,
                            "PAGE",
                            8L,
                            2L,
                            "权限",
                            0,
                            40,
                            "成员角色 OWNER EDITOR VIEWER 控制知识库访问"));

    private AgenticAnswerService service(
            ChildRecallPort bm25,
            ChildRecallPort vector,
            ParentEvidenceResolver.ParentChunkFetcher parents,
            com.kwiki.rag.answer.AnswerLlmPort llm,
            ScopeVersionService scopeVersions) {
        var metrics = new SimpleMeterRegistry();
        var budgets = new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5));
        return com.kwiki.testutil.AgenticTestSupport.service(
                new RuleFirstRouter(
                        KeywordRuleSet.defaults(),
                        StandardTestProperties.nullProvider(),
                        false,
                        metrics),
                new QueryRewriteOrchestrator(
                        new RewriteDecisionService(),
                        new com.kwiki.rag.rewrite.ConversationalRewriter(Optional.empty()),
                        new com.kwiki.rag.rewrite.ExpansionRewriter(Optional.empty()),
                        new com.kwiki.rag.rewrite.DecompositionRewriter(Optional.empty()),
                        metrics),
                new HybridRetrievalOrchestrator(
                        texts -> List.of(new float[] {0.5f}),
                        new ConcurrentRecallService(bm25, vector),
                        new ParentEvidenceResolver(Optional.of(parents)),
                        scopeVersions,
                        budgets),
                new com.kwiki.rag.answer.EvidenceAssembler(budgets),
                llm,
                new AuthorizationScopeResolver(
                        Mockito.mock(KnowledgeBaseMemberRepository.class),
                        scopeVersions,
                        new com.kwiki.infrastructure.redis.ScopeCache(
                                StandardTestProperties.nullProvider(), Duration.ofSeconds(60))),
                new com.kwiki.rag.answer.ChatPersistenceService(
                        StandardTestProperties.nullProvider()),
                metrics,
                scopeVersions);
    }

    private static ChildRecallPort matchingBothBranches() {
        return (query, vector, filter, topK) ->
                CORPUS.stream()
                        .filter(hit -> query.contains("部署") ? hit.chunkKey().contains(":7:") : true)
                        .limit(topK)
                        .toList();
    }

    private static ParentEvidenceResolver.ParentChunkFetcher parentFetcher() {
        return (keys, filter) ->
                keys.stream()
                        .map(
                                key ->
                                        new ParentEvidenceChunk(
                                                key,
                                                1L,
                                                "PAGE",
                                                7L,
                                                3L,
                                                "部署",
                                                "# 部署\nkwiki 连接外部中间件，绝不创建容器。",
                                                0.4,
                                                CORPUS.stream()
                                                        .filter(
                                                                hit ->
                                                                        hit.parentChunkKey()
                                                                                .equals(key))
                                                        .toList()))
                        .toList();
    }

    @Test
    void markdownToSseCitationsFlowsEndToEnd() {
        List<ChatStreamEvent> events =
                service(
                                matchingBothBranches(),
                                matchingBothBranches(),
                                parentFetcher(),
                                prompt -> {
                                    assertThat(prompt)
                                            .contains("Question: 什么是 kwiki 的部署方式")
                                            .contains("绝不创建容器");
                                    return Flux.just("kwiki ", "连接外部中间件。[P0]");
                                },
                                new ScopeVersionService(StandardTestProperties.nullProvider()))
                        .answer(ADMIN, "什么是 kwiki 的部署方式")
                        .collectList()
                        .block(Duration.ofSeconds(5));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        assertThat(types.subList(0, 3)).containsExactly("route", "route", "rewrite");
        assertThat(types).contains("retrieve", "token", "citations");
        assertThat(types.get(types.size() - 1)).isEqualTo("done");

        ChatStreamEvent citations = events.get(types.indexOf("citations"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) citations.payloadMap().get("citations");
        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0))
                .containsEntry("childChunkKey", "PAGE:7:3:P0:C0")
                .containsKey("headingPath");
    }

    @Test
    void accessRevocationDuringGenerationAbortsTheStream() {
        CurrentUser member = new CurrentUser(7L, "member", false);
        KnowledgeBaseMemberRepository memberRepo =
                Mockito.mock(KnowledgeBaseMemberRepository.class);
        Mockito.when(memberRepo.findByUserId(7L))
                .thenReturn(
                        java.util.List.of(
                                new com.kwiki.wiki.domain.KnowledgeBaseMember(
                                        1L,
                                        7L,
                                        com.kwiki.wiki.access.KnowledgeBaseRole.VIEWER,
                                        1L)));

        ScopeVersionService scopeVersions =
                new ScopeVersionService(StandardTestProperties.nullProvider());
        // scope resolves with version 1; revocation lands mid-flight during retrieval
        ParentEvidenceResolver.ParentChunkFetcher revokingFetcher =
                (keys, filter) -> {
                    scopeVersions.bump(1L);
                    return parentFetcher().fetchByKeys(keys, filter);
                };

        var metrics = new SimpleMeterRegistry();
        var budgets = new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5));
        AgenticAnswerService revokingService =
                com.kwiki.testutil.AgenticTestSupport.service(
                        new RuleFirstRouter(
                                KeywordRuleSet.defaults(),
                                StandardTestProperties.nullProvider(),
                                false,
                                metrics),
                        new QueryRewriteOrchestrator(
                                new RewriteDecisionService(),
                                new com.kwiki.rag.rewrite.ConversationalRewriter(Optional.empty()),
                                new com.kwiki.rag.rewrite.ExpansionRewriter(Optional.empty()),
                                new com.kwiki.rag.rewrite.DecompositionRewriter(Optional.empty()),
                                metrics),
                        new HybridRetrievalOrchestrator(
                                texts -> List.of(new float[] {0.5f}),
                                new ConcurrentRecallService(
                                        matchingBothBranches(), matchingBothBranches()),
                                new ParentEvidenceResolver(Optional.of(revokingFetcher)),
                                scopeVersions,
                                budgets),
                        new com.kwiki.rag.answer.EvidenceAssembler(budgets),
                        prompt -> Flux.just("never used"),
                        new AuthorizationScopeResolver(
                                memberRepo,
                                scopeVersions,
                                new com.kwiki.infrastructure.redis.ScopeCache(
                                        StandardTestProperties.nullProvider(),
                                        Duration.ofSeconds(60))),
                        new com.kwiki.rag.answer.ChatPersistenceService(
                                StandardTestProperties.nullProvider()),
                        metrics,
                        scopeVersions);

        List<ChatStreamEvent> events =
                revokingService
                        .answer(member, "什么是 kwiki 的部署方式")
                        .collectList()
                        .block(Duration.ofSeconds(5));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        assertThat(types).doesNotContain("token", "citations");
        assertThat(types.get(types.size() - 1)).isEqualTo("error");
        assertThat(events.get(types.size() - 1).payloadMap())
                .containsEntry("error", "authorization-changed");
    }
}
