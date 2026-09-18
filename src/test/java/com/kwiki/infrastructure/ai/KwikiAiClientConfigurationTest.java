package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.infrastructure.config.ExternalServicesProperties;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

class KwikiAiClientConfigurationTest {

    @Test
    void discoversProviderOutputLimitDuringInitialization() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"id\":\"answer-model\",\"capabilities\":{\"max_output_tokens\":32768}}"));

            var properties = new ExternalServicesProperties(
                    new ExternalServicesProperties.ContentCenter(
                            "http://content", "token", "", "", Duration.ofSeconds(1),
                            Duration.ofSeconds(1)),
                    new ExternalServicesProperties.Elasticsearch("", "", ""),
                    new ExternalServicesProperties.AnswerLlm(
                            server.url("/v1").toString(), "provider-key", "answer-model",
                            Duration.ofSeconds(2)),
                    new ExternalServicesProperties.QwenEmbedding(
                            "http://embedding", "key", "embedding-model", 1024,
                            Duration.ofSeconds(1)),
                ExternalServicesProperties.unusedVisionModel());

            AnswerModelCapabilities capabilities = new KwikiAiClientConfiguration()
                    .kwikiAnswerModelCapabilities(properties, WebClient.builder());

            assertThat(capabilities.maxOutputTokens()).isEqualTo(32768);
            assertThat(capabilities.source()).isEqualTo("provider:$.capabilities.max_output_tokens");
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/v1/models/answer-model");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer provider-key");
        }
    }

    @Test
    void readsOutputLimitFromMatchingModelInListFallback() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"id\":\"other\",\"max_tokens\":1},"
                            + "{\"id\":\"answer-model\",\"max_completion_tokens\":8192}]}"));

            var properties = properties(server.url("/v1").toString());
            AnswerModelCapabilities capabilities = new KwikiAiClientConfiguration()
                    .kwikiAnswerModelCapabilities(properties, WebClient.builder());

            assertThat(capabilities.maxOutputTokens()).isEqualTo(8192);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    private static ExternalServicesProperties properties(String answerBaseUrl) {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "http://content", "token", "", "", Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new ExternalServicesProperties.Elasticsearch("", "", ""),
                new ExternalServicesProperties.AnswerLlm(
                        answerBaseUrl, "provider-key", "answer-model", Duration.ofSeconds(2)),
                new ExternalServicesProperties.QwenEmbedding(
                        "http://embedding", "key", "embedding-model", 1024,
                        Duration.ofSeconds(1)),
                ExternalServicesProperties.unusedVisionModel());
    }
}
