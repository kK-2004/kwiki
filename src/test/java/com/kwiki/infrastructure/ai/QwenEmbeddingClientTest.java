package com.kwiki.infrastructure.ai;

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
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP stub contract for the Qwen embedding client: request shape (model, batching,
 * kwiki credential header), dimension validation, transient retry, non-retryable
 * rejection, and credential-free errors. No real endpoint is contacted.
 */
class QwenEmbeddingClientTest {

    private MockWebServer server;
    private QwenEmbeddingClient client;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/compatible-mode/v1").toString())
                .defaultHeader("Authorization", "Bearer test-embedding-key")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
                .build();
        client = new QwenEmbeddingClient(webClient, properties(), 2, 2);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static ExternalServicesProperties properties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc:8080", "kapp-test",
                        null, null, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch( null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m", Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "test-embedding-key",
                        "text-embedding-v4", 4, Duration.ofSeconds(10)));
    }

    private static String embeddingJson(int count, int dims) {
        String vectors = IntStream.range(0, count)
                .mapToObj(i -> "{\"index\":" + i + ",\"embedding\":"
                        + vectorJson(dims) + "}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"data\":[" + vectors + "]}";
    }

    private static String vectorJson(int dims) {
        return "[" + IntStream.range(0, dims).mapToObj(i -> "0.25").collect(
                java.util.stream.Collectors.joining(",")) + "]";
    }

    @Test
    void embedSendsModelAndCredentialAndReturnsVectors() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(embeddingJson(2, 4)));

        List<float[]> vectors = client.embed(List.of("第一段", "第二段"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(4);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/compatible-mode/v1/embeddings");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-embedding-key");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"text-embedding-v4\"");
        assertThat(body).contains("第一段").contains("第二段");
    }

    @Test
    void largeInputIsSplitIntoBatches() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(embeddingJson(2, 4)));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(embeddingJson(1, 4)));

        List<float[]> vectors = client.embed(List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(server.getRequestCount()).isEqualTo(2);
        server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(second.getBody().readUtf8()).contains("\"c\"");
    }

    @Test
    void dimensionMismatchIsRejected() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(embeddingJson(1, 7)));

        assertThatThrownBy(() -> client.embed(List.of("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension mismatch");
    }

    @Test
    void transientFailuresAreRetriedWithinLimit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(embeddingJson(1, 4)));

        List<float[]> vectors = client.embed(List.of("x"));

        assertThat(vectors).hasSize(1);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void exhaustedRetriesFailWithCredentialFreeError() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThatThrownBy(() -> client.embed(List.of("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retries exhausted")
                .hasMessageNotContaining("test-embedding-key");
    }

    @Test
    void clientErrorsAreNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(400));

        assertThatThrownBy(() -> client.embed(List.of("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
