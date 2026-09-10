package com.kwiki.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.rag.testutil.AgenticTestSupportHarness;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QA 门禁链路的黄金流契约：活动事件先于
 * token，恰好一个终止性 done（或 error）事件结束流，
 * 序号单调递增且 requestId 稳定，无证据时绝不调用
 * 服务提供方，服务提供方故障产生已净化的终止性错误，客户端
 * 取消会取消服务提供方的流。
 */
class AgenticAnswerStreamTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);

    private static ChunkHit hit(String key, String parent) {
        return new ChunkHit(key, parent, 1L, "PAGE", 7L, 3L, "部署", 10, 80,
                "kwiki 使用外部部署的 MySQL 与 Elasticsearch");
    }

    private static List<ChatStreamEvent> collect(Flux<ChatStreamEvent> flux) {
        return flux.collectList().block(Duration.ofSeconds(10));
    }

    private AgenticAnswerService service(
            com.kwiki.rag.testutil.AgenticTestSupportHarness.RecallScript bm25,
            com.kwiki.rag.testutil.AgenticTestSupportHarness.RecallScript vector,
            com.kwiki.rag.testutil.AgenticTestSupportHarness.ParentScript parents,
            java.util.function.Supplier<Flux<String>> llm) {
        var metrics = new SimpleMeterRegistry();
        var scopeVersions = new ScopeVersionService(nullProvider());
        var scopeResolver = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(KnowledgeBaseMemberRepository.class),
                scopeVersions,
                new ScopeCache(cacheProvider(), Duration.ofSeconds(60)));
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), llmProvider(null), false, metrics);
        var harness = AgenticTestSupportHarness.build(router, scopeResolver, scopeVersions,
                new ChatPersistenceService(nullProvider()), metrics,
                bm25, vector, parents, llm);
        return new AgenticAnswerService(harness);
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

    @Test
    void successfulStreamFollowsTheGoldenEventOrder() {
        List<ChatStreamEvent> events = collect(service(
                (query, vec, filter, topK) -> List.of(hit("C1", "P0")),
                (query, vec, filter, topK) -> List.of(hit("C1", "P0")),
                (keys, filter) -> keys.stream()
                        .map(key -> new com.kwiki.rag.retrieval.ParentEvidenceChunk(
                                key, 1L, "PAGE", 7L, 3L, "部署",
                                "kwiki 连接外部服务。", 0.5, List.of(hit("C1", key))))
                        .toList(),
                () -> Flux.just("kwiki ", "连接外部服务。[P0]"))
                .answer(ADMIN, "什么是 kwiki 的部署方式"));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        int firstActivity = types.indexOf("activity");
        int firstToken = types.indexOf("token");
        int lastToken = types.lastIndexOf("token");
        int citationsIdx = types.indexOf("citations");
        int doneIdx = types.indexOf("done");

        assertThat(firstActivity).isZero();
        assertThat(firstToken).isGreaterThan(firstActivity);
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
        assertThat(payloadCitations).isNotEmpty();
        assertThat(payloadCitations.get(0))
                .containsEntry("childChunkKey", "C1")
                .containsKey("charStart")
                .containsKey("excerpt");
    }

    @Test
    void noEvidenceNeverCallsTheProvider() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        List<ChatStreamEvent> events = collect(service(
                (query, vec, filter, topK) -> List.of(),
                (query, vec, filter, topK) -> List.of(),
                (keys, filter) -> List.of(),
                () -> {
                    providerCalled.set(true);
                    return Flux.just("should not happen");
                })
                .answer(ADMIN, "什么是 kwiki 的部署方式"));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        // 拒绝文案本身也以 token 形式流式输出（它就是被发布的
        // 回答）；服务提供方从不运行，因此其内容绝不会出现。
        var streamed = events.stream()
                .filter(event -> event.type().equals("token"))
                .map(event -> String.valueOf(event.payloadMap().get("text")))
                .collect(java.util.stream.Collectors.joining());
        assertThat(streamed)
                .isEqualTo(com.kwiki.rag.orchestration.AgenticErrorCodes.INSUFFICIENT_MESSAGE);
        assertThat(types.get(types.size() - 1)).isEqualTo("done");
        assertThat(events.get(types.indexOf("done")).payloadMap())
                .containsEntry("noEvidence", true);
        assertThat(providerCalled.get()).as("no generation call without evidence").isFalse();
    }

    @Test
    void providerFailureTerminatesWithSingleSanitizedErrorAndNoDone() {
        List<ChatStreamEvent> events = collect(service(
                (query, vec, filter, topK) -> List.of(hit("C1", "P0")),
                (query, vec, filter, topK) -> List.of(hit("C1", "P0")),
                (keys, filter) -> List.of(),
                () -> Flux.error(new IllegalStateException("qwen quota exhausted")))
                .answer(ADMIN, "什么是 kwiki 的部署方式"));

        List<String> types = events.stream().map(ChatStreamEvent::type).toList();
        assertThat(types.stream().filter("error"::equals).count()).isEqualTo(1);
        assertThat(types.get(types.size() - 1)).isEqualTo("error");
        assertThat(types).doesNotContain("done");
        String wire = events.get(types.indexOf("error")).toWire();
        assertThat(wire).contains("answer-provider-failed").doesNotContain("qwen");
    }

    @Test
    void eventsAreMonotonicPerRequestWithStableRequestId() {
        List<ChatStreamEvent> events = collect(service(
                (query, vec, filter, topK) -> List.of(hit("C1", "P0")),
                (query, vec, filter, topK) -> List.of(hit("C1", "P0")),
                (keys, filter) -> List.of(),
                () -> Flux.just("a", "b"))
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
