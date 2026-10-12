package com.kwiki.infrastructure.ai.registry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiModelClientFactoryTest {

    private final OpenAiModelClientFactory factory =
            new OpenAiModelClientFactory(WebClient.builder(), Optional.empty(), Optional.empty());

    @Test
    void 从模型元数据探测输出上限() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"id\":\"answer-model\",\"capabilities\":{\"max_output_tokens\":32768}}"));
            Integer limit = factory.probeOutputLimit(server.url("/v1").toString(), "provider-key",
                    Duration.ofSeconds(2), "answer-model");
            assertThat(limit).isEqualTo(32768);
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/v1/models/answer-model");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer provider-key");
        }
    }

    @Test
    void 单模型端点不可用时回退到列表并匹配模型() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"id\":\"other\",\"max_tokens\":1},"
                            + "{\"id\":\"answer-model\",\"max_completion_tokens\":8192}]}"));
            assertThat(factory.probeOutputLimit(server.url("/v1").toString(), "k", Duration.ofSeconds(2),
                    "answer-model")).isEqualTo(8192);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void 元数据不可用时返回空() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            assertThat(factory.probeOutputLimit(server.url("/v1").toString(), "k", Duration.ofSeconds(2),
                    "m")).isNull();
        }
    }

    @Test
    void 列出模型id() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"id\":\"qwen-max\"},{\"id\":\"text-embedding-v4\"}]}"));
            assertThat(factory.listModels(server.url("/v1/").toString(), "k", Duration.ofSeconds(2)))
                    .containsExactly("qwen-max", "text-embedding-v4");
            assertThat(server.takeRequest().getPath()).isEqualTo("/v1/models");
        }
    }
}
