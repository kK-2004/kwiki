package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.infrastructure.config.ExternalServicesProperties;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP-stub contract for the router adapter: valid JSON (also fenced) parses, timeouts/invalid
 * JSON/provider errors return empty for deterministic fallback, and requests carry no credential in
 * URLs (only the sanitized header).
 */
class RouterLlmAdapterTest {

    private MockWebServer server;
    private RouterLlmAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        WebClient webClient =
                WebClient.builder()
                        .baseUrl(server.url("/v1").toString())
                        .defaultHeader("Authorization", "Bearer test-answer-key")
                        .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
                        .build();
        adapter =
                new RouterLlmAdapter(
                        dev.langchain4j.model.openai.OpenAiChatModel.builder()
                                .baseUrl(server.url("/v1").toString())
                                .apiKey("test-answer-key")
                                .modelName("qwen-max")
                                .maxRetries(0)
                                .timeout(Duration.ofSeconds(2))
                                .httpClientBuilder(new CancellableModelHttpClient.Builder())
                                .build());
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static ExternalServicesProperties properties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "http://cc:8080",
                        "kapp-test",
                        null,
                        null,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm(
                        "http://l/v1", "k", "qwen-max", Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding(
                        "http://q/v1", "k", "text-embedding-v4", 4, Duration.ofSeconds(10)));
    }

    @Test
    void validJsonDecisionIsParsed() throws Exception {
        String json =
                "{\"intent\":\"PROCEDURAL\",\"needsRetrieval\":true,"
                        + "\"rewriteMode\":\"NONE\",\"subqueries\":[],\"confidence\":0.9}";
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                "{\"choices\":[{\"message\":{\"content\":" + quote(json) + "}}]}"));

        Optional<Map<String, Object>> decision = adapter.askRouter("怎么部署", "no-rule-match");

        assertThat(decision).isPresent();
        assertThat(decision.get().get("intent")).hasToString("PROCEDURAL");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(request.getBody().readUtf8()).contains("怎么部署").contains("no-rule-match");
    }

    @Test
    void markdownFencedJsonIsTolerated() {
        String fenced =
                "```json\n{\"intent\":\"KNOWLEDGE_QA\",\"needsRetrieval\":true,"
                        + "\"rewriteMode\":\"NONE\",\"subqueries\":[],\"confidence\":0.7}\n```";
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                "{\"choices\":[{\"message\":{\"content\":"
                                        + quote(fenced)
                                        + "}}]}"));

        Optional<Map<String, Object>> decision = adapter.askRouter("随便聊聊", "no-rule-match");
        assertThat(decision).isPresent();
        assertThat(decision.get().get("intent")).hasToString("KNOWLEDGE_QA");
    }

    @Test
    void invalidJsonReturnsEmpty() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                "{\"choices\":[{\"message\":{\"content\":\"not json at all\"}}]}"));
        assertThat(adapter.askRouter("q", "no-rule-match")).isEmpty();
    }

    @Test
    void providerErrorReturnsEmpty() {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(adapter.askRouter("q", "no-rule-match")).isEmpty();
    }

    @Test
    void timeoutReturnsEmpty() {
        server.enqueue(
                new MockResponse()
                        .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"choices\":[]}"));
        assertThat(adapter.askRouter("q", "no-rule-match")).isEmpty();
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
