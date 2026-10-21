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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量嵌入的用量采集契约：解析响应里的 usage.prompt_tokens、默认步骤为查询向量、
 * 索引作用域覆盖为索引向量、被 provider 拒绝的请求记为失败且不影响调用结果。
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
        WebClient web = WebClient.builder().baseUrl(server.url("/v1").toString())
                .defaultHeader("Content-Type", "application/json").build();
        return new QwenEmbeddingClient(web, "text-embedding-v4", 2, 10, 0, Map.of(), "dashscope", recorder);
    }

    static MockResponse ok(String usage) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]" + usage + "}");
    }

    @Test
    void queryEmbeddingRecordsPromptTokens() {
        server.enqueue(ok(",\"usage\":{\"prompt_tokens\":12,\"total_tokens\":12}"));

        client().embed(List.of("问题"));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.step()).isEqualTo(UsageSteps.QUERY_EMBEDDING);
        assertThat(event.provider()).isEqualTo("dashscope");
        assertThat(event.model()).isEqualTo("text-embedding-v4");
        assertThat(event.inputTokens()).isEqualTo(12);
        assertThat(event.outputTokens()).isZero();
        assertThat(event.source()).isEqualTo(UsageSource.BACKGROUND);
        assertThat(event.success()).isTrue();
    }

    @Test
    void indexScopeMarksIndexEmbeddingAndMissingUsageCountsZero() {
        server.enqueue(ok(""));

        try (var scope = ModelUsageContext.open(UsageSource.BACKGROUND, 0L, UsageSteps.INDEX_EMBEDDING)) {
            client().embed(List.of("段落"));
        }

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.step()).isEqualTo(UsageSteps.INDEX_EMBEDDING);
        assertThat(event.inputTokens()).isZero();
    }

    @Test
    void rejectedRequestIsRecordedAsFailure() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"Arrearage\"}"));

        assertThatThrownBy(() -> client().embed(List.of("问题"))).isInstanceOf(IllegalStateException.class);

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.success()).isFalse();
    }
}
