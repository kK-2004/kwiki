package com.kwiki.infrastructure.ai;

import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.ModelUsageRecorder;
import com.kwiki.usage.UsageEvent;
import com.kwiki.usage.UsageSource;
import com.kwiki.usage.UsageSteps;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量嵌入的用量采集契约：解析响应里的 usage.prompt_tokens、默认步骤为查询向量、
 * 索引作用域覆盖为索引向量、每批 HTTP 请求一条事件、失败按稳定错误码区分。
 */
class QwenEmbeddingUsageTest {

    MockWebServer server;
    final ModelUsageRecorder recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    QwenEmbeddingClient client() {
        return client(10, 0);
    }

    QwenEmbeddingClient client(int batchSize, int maxRetries) {
        WebClient web = WebClient.builder().baseUrl(server.url("/v1").toString())
                .defaultHeader("Content-Type", "application/json").build();
        return new QwenEmbeddingClient(web, "text-embedding-v4", 2, batchSize, maxRetries, Map.of(),
                "dashscope", recorder);
    }

    static MockResponse ok(String usage) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]" + usage + "}");
    }

    /** 指定条数、维度与用量的成功响应。 */
    static MockResponse ok(int count, int dimensions, String usage) {
        String items = IntStream.range(0, count)
                .mapToObj(i -> "{\"index\":" + i + ",\"embedding\":" + vector(dimensions) + "}")
                .collect(java.util.stream.Collectors.joining(","));
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[" + items + "]" + usage + "}");
    }

    private static String vector(int dimensions) {
        return "[" + IntStream.range(0, dimensions)
                .mapToObj(i -> "0.1")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    @Test
    void queryEmbeddingRecordsPromptTokens() {
        server.enqueue(ok(",\"usage\":{\"prompt_tokens\":12,\"total_tokens\":12}"));

        client().embed(List.of("问题"));

        List<UsageEvent> events = recorder.drain();
        assertThat(events).hasSize(1);
        UsageEvent event = events.getFirst();
        assertThat(event.step()).isEqualTo(UsageSteps.QUERY_EMBEDDING);
        assertThat(event.provider()).isEqualTo("dashscope");
        assertThat(event.model()).isEqualTo("text-embedding-v4");
        assertThat(event.inputTokens()).isEqualTo(12);
        assertThat(event.outputTokens()).isZero();
        assertThat(event.source()).isEqualTo(UsageSource.BACKGROUND);
        assertThat(event.success()).isTrue();
        assertThat(event.errorType()).isNull();
    }

    @Test
    void indexScopeMarksIndexEmbeddingAndMissingUsageCountsZero() {
        server.enqueue(ok(""));

        try (var scope = ModelUsageContext.open(UsageSource.BACKGROUND, 0L, UsageSteps.INDEX_EMBEDDING)) {
            client().embed(List.of("段落"));
        }

        List<UsageEvent> events = recorder.drain();
        assertThat(events).hasSize(1);
        UsageEvent event = events.getFirst();
        assertThat(event.step()).isEqualTo(UsageSteps.INDEX_EMBEDDING);
        assertThat(event.inputTokens()).isZero();
    }

    @Test
    void rejectedRequestIsRecordedAsFailure() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"Arrearage\"}"));

        assertThatThrownBy(() -> client().embed(List.of("问题"))).isInstanceOf(IllegalStateException.class);

        List<UsageEvent> events = recorder.drain();
        assertThat(events).hasSize(1);
        UsageEvent event = events.getFirst();
        assertThat(event.success()).isFalse();
        assertThat(event.errorType()).isEqualTo("rejected-400");
    }

    @Test
    void 瞬时故障重试耗尽记为_retries_exhausted() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThatThrownBy(() -> client(10, 2).embed(List.of("问题")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retries exhausted");

        List<UsageEvent> events = recorder.drain();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().success()).isFalse();
        assertThat(events.getFirst().errorType()).isEqualTo("retries-exhausted");
    }

    @Test
    void 维度不符记为_dimension_mismatch() {
        server.enqueue(ok(1, 3, ""));

        assertThatThrownBy(() -> client().embed(List.of("问题")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension mismatch");

        List<UsageEvent> events = recorder.drain();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().success()).isFalse();
        assertThat(events.getFirst().errorType()).isEqualTo("dimension-mismatch");
    }

    @Test
    void 返回条数不符记为_count_mismatch() {
        server.enqueue(ok(2, 2, ""));

        assertThatThrownBy(() -> client().embed(List.of("问题")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("count mismatch");

        List<UsageEvent> events = recorder.drain();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().success()).isFalse();
        assertThat(events.getFirst().errorType()).isEqualTo("count-mismatch");
    }

    @Test
    void 多批次按批次各记一条事件() {
        server.enqueue(ok(2, 2, ",\"usage\":{\"prompt_tokens\":5}"));
        server.enqueue(ok(1, 2, ",\"usage\":{\"prompt_tokens\":3}"));

        client(2, 0).embed(List.of("一", "二", "三"));

        List<UsageEvent> events = recorder.drain();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(UsageEvent::inputTokens).containsExactly(5, 3);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.step()).isEqualTo(UsageSteps.QUERY_EMBEDDING);
            assertThat(event.success()).isTrue();
        });
    }
}
