package com.kwiki.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.HybridRetrievalOrchestrator;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.ParentEvidenceResolver;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.rewrite.QueryRewriteOrchestrator;
import com.kwiki.rag.rewrite.RewriteDecisionService;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Golden-stream contract: progress events precede tokens, one citations event follows tokens,
 * exactly one terminal done (or error) event ends the stream, sequences are monotonic, no-evidence
 * short-circuits without a provider call, provider failure produces a sanitized terminal error, and
 * client cancellation cancels the provider stream.
 */
class AgenticAnswerStreamTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);

    private static ChunkHit hit(String key, String parent) {
        return new ChunkHit(
                key,
                parent,
                1L,
                "PAGE",
                7L,
                3L,
                "部署",
                10,
                80,
                "kwiki 使用外部部署的 MySQL 与 Elasticsearch");
    }

    /** Streams the service output to completion, capturing the raw events. */
    private static List<ChatStreamEvent> collect(Flux<ChatStreamEvent> flux) {
        return flux.collectList().block(Duration.ofSeconds(5));
    }

    private AgenticAnswerService service(
            ChildRecallPort bm25,
            ChildRecallPort vector,
            ParentEvidenceResolver.ParentChunkFetcher parents,
            AnswerLlmPort llm) {
        var metrics = new SimpleMeterRegistry();
        var budgets = new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5));
        var scopeVersions = new ScopeVersionService(nullProvider());
        var scopeResolver =
                new AuthorizationScopeResolver(
                        memberRepo(),
                        scopeVersions,
                        new ScopeCache(cacheProvider(), Duration.ofSeconds(60)));
        var router =
                new RuleFirstRouter(
                        com.kwiki.rag.routing.KeywordRuleSet.defaults(),
                        llmProvider(null),
                        false,
                        metrics);
        var rewrites =
                new QueryRewriteOrchestrator(
                        new RewriteDecisionService(),
                        new com.kwiki.rag.rewrite.ConversationalRewriter(Optional.empty()),
                        new com.kwiki.rag.rewrite.ExpansionRewriter(Optional.empty()),
                        new com.kwiki.rag.rewrite.DecompositionRewriter(Optional.empty()),
                        metrics);
        var retrieval =
                new HybridRetrievalOrchestrator(
                        embeddings(),
                        new ConcurrentRecallService(bm25, vector),
                        new ParentEvidenceResolver(Optional.of(parents)),
                        scopeVersions,
                        budgets);
        var registry = new com.kwiki.rag.tool.ToolRegistry();
        com.kwiki.rag.tool.RetrievalPlannerPort planner =
                (q, queries, gaps, history, error) ->
                        List.of(
                                new com.kwiki.rag.tool.ToolCall(
                                        "call-" + history.size(),
                                        "es_search",
                                        com.kwiki.rag.tool.ToolRegistry.json(
                                                new com.kwiki.rag.tool.SearchArguments(
                                                        queries,
                                                        com.kwiki.rag.retrieval.RetrievalStrategy
                                                                .HYBRID,
                                                        50)),
                                        "turn-" + history.size()));
        com.kwiki.rag.quality.QualityAnalyzerPort qa =
                (q, queries, evidence) ->
                        new com.kwiki.rag.quality.QualityDecision(
                                com.kwiki.rag.quality.QualityDecision.Action.GENERATE,
                                true,
                                evidence.stream().map(ParentEvidence::parentChunkKey).toList(),
                                List.of(),
                                "sufficient",
                                List.of(),
                                com.kwiki.rag.quality.QualityDecision.ReturnKind.NONE);
        try {
            return new AgenticAnswerService(
                    new com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow(
                            router,
                            rewrites,
                            retrieval,
                            new EvidenceAssembler(budgets),
                            llm,
                            qa,
                            planner,
                            registry,
                            new com.kwiki.rag.tool.ManualToolDispatcher(registry, retrieval),
                            scopeResolver,
                            scopeVersions,
                            new ChatPersistenceService(nullProvider()),
                            metrics,
                            com.kwiki.rag.orchestration.AgenticLimits.defaults(),
                            Optional.empty(),
                            Optional.empty()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider nullProvider() {
        return new ObjectProvider() {
            @Override
            public Object getIfAvailable() {
                return null;
            }
        };
    }

    private static ObjectProvider<com.kwiki.rag.routing.RouterLlmPort> llmProvider(
            com.kwiki.rag.routing.RouterLlmPort port) {
        return new ObjectProvider<>() {
            @Override
            public com.kwiki.rag.routing.RouterLlmPort getIfAvailable() {
                return port;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<com.kk2004.common.redis.RedisUtil> cacheProvider() {
        return new ObjectProvider() {
            @Override
            public Object getIfAvailable() {
                return null;
            }
        };
    }

    private static KnowledgeBaseMemberRepository memberRepo() {
        return org.mockito.Mockito.mock(KnowledgeBaseMemberRepository.class);
    }

    private static ChunkEmbeddingPort embeddings() {
        return texts -> texts.stream().map(text -> new float[] {0.1f}).toList();
    }

    private static ChildRecallPort branchWithHits() {
        return (query, vector, filter, topK) -> List.of(hit("C1", "P0"));
    }

    private static ChildRecallPort emptyBranch() {
        return (query, vector, filter, topK) -> List.of();
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
                                                "kwiki 连接外部 MySQL、Redis、内容中心与"
                                                    + " Elasticsearch，绝不自建容器。",
                                                0.5,
                                                List.of(hit("C1", key))))
                        .toList();
    }

    @Test
    void successfulStreamFollowsTheGoldenEventOrder() {
        List<ChatStreamEvent> events =
                collect(
                        service(
                                        branchWithHits(),
                                        branchWithHits(),
                                        parentFetcher(),
                                        prompt -> Flux.just("kwiki ", "连接外部服务。[P0]"))
                                .answer(ADMIN, "什么是 kwiki 的部署方式"));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        int routeIdx = types.indexOf("route");
        int rewriteIdx = types.indexOf("rewrite");
        int retrieveIdx = types.indexOf("retrieve");
        int firstToken = types.indexOf("token");
        int lastToken = types.lastIndexOf("token");
        int citationsIdx = types.indexOf("citations");
        int doneIdx = types.indexOf("done");

        assertThat(routeIdx).isZero();
        assertThat(rewriteIdx).isGreaterThan(routeIdx);
        assertThat(retrieveIdx).isGreaterThan(rewriteIdx);
        assertThat(firstToken).isGreaterThan(retrieveIdx);
        assertThat(citationsIdx).isGreaterThan(lastToken);
        assertThat(doneIdx).isEqualTo(types.size() - 1);
        assertThat(types.stream().filter("done"::equals).count()).isEqualTo(1);
        assertThat(types.stream().filter("error"::equals).count()).isZero();

        List<Long> sequences = events.stream().map(ChatStreamEvent::sequence).toList();
        assertThat(sequences).isSorted();
        assertThat(sequences.get(0)).isEqualTo(1);

        ChatStreamEvent citations = events.get(citationsIdx);
        List<Map<String, Object>> payloadCitations =
                (List<Map<String, Object>>) citations.payloadMap().get("citations");
        assertThat(payloadCitations).hasSize(1);
        assertThat(payloadCitations.get(0))
                .containsEntry("childChunkKey", "C1")
                .containsEntry("parentChunkKey", "P0")
                .containsKey("charStart")
                .containsKey("excerpt");
    }

    @Test
    void noEvidenceShortCircuitsWithoutCallingTheProvider() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        List<ChatStreamEvent> events =
                collect(
                        service(
                                        emptyBranch(),
                                        emptyBranch(),
                                        parentFetcher(),
                                        prompt -> {
                                            providerCalled.set(true);
                                            return Flux.just("should not happen");
                                        })
                                .answer(ADMIN, "什么是 kwiki 的部署方式"));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        assertThat(types).contains("retrieve").doesNotContain("token");
        assertThat(types.get(types.size() - 1)).isEqualTo("done");
        assertThat(events.get(types.indexOf("done")).payloadMap())
                .containsEntry("noEvidence", true);
        assertThat(providerCalled.get()).as("no generation call without evidence").isFalse();
    }

    @Test
    void providerFailureTerminatesWithSingleSanitizedErrorAndNoDone() {
        List<ChatStreamEvent> events =
                collect(
                        service(
                                        branchWithHits(),
                                        branchWithHits(),
                                        parentFetcher(),
                                        prompt ->
                                                Flux.error(
                                                        new IllegalStateException(
                                                                "qwen quota exhausted")))
                                .answer(ADMIN, "什么是 kwiki 的部署方式"));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        assertThat(types.stream().filter("error"::equals).count()).isEqualTo(1);
        assertThat(types.get(types.size() - 1)).isEqualTo("error");
        assertThat(types).doesNotContain("done");
        String wire = events.get(types.indexOf("error")).toWire();
        assertThat(wire).contains("answer-provider-failed").doesNotContain("qwen");
    }

    @Test
    void clientCancellationCancelsTheProviderStream() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Sinks.Many<String> providerSink = Sinks.many().unicast().onBackpressureBuffer();
        // unicast sink: attach doOnCancel inside the flux handed to the service
        Flux<String> providerFlux = providerSink.asFlux().doOnCancel(() -> cancelled.set(true));

        AgenticAnswerService service =
                service(
                        branchWithHits(),
                        branchWithHits(),
                        parentFetcher(),
                        prompt -> providerFlux);

        var disposable = service.answer(ADMIN, "什么是 kwiki 的部署方式").subscribe();
        for (int i = 0; i < 100 && providerSink.currentSubscriberCount() == 0; i++)
            Thread.sleep(20);
        assertThat(providerSink.currentSubscriberCount()).isEqualTo(1);
        providerSink.tryEmitNext("部分");
        disposable.dispose();

        for (int i = 0; i < 40 && !cancelled.get(); i++) {
            Thread.sleep(50);
        }
        assertThat(cancelled.get()).as("provider stream must be cancelled").isTrue();
    }

    @Test
    void eventsAreMonotonicPerRequestWithStableRequestId() {
        List<ChatStreamEvent> events =
                collect(
                        service(
                                        branchWithHits(),
                                        branchWithHits(),
                                        parentFetcher(),
                                        prompt -> Flux.just("a", "b"))
                                .answer(ADMIN, "什么是 kwiki 的部署方式"));

        String requestId = events.get(0).requestId();
        assertThat(requestId).isNotBlank();
        assertThat(events).allSatisfy(event -> assertThat(event.requestId()).isEqualTo(requestId));
    }

    @Test
    void wireFormatIncludesSeqRequestIdAndType() {
        ChatStreamEvent event = ChatStreamEvent.of("token", 3, "req-1", Map.of("text", "你好"));
        String wire = event.toWire();
        assertThat(wire).startsWith("event: token\ndata: ");
        assertThat(wire)
                .contains("\"seq\":3")
                .contains("\"requestId\":\"req-1\"")
                .contains("\"type\":\"token\"")
                .contains("\"text\":\"你好\"")
                .endsWith("\n\n");
    }
}
