package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArcadeDbHttpAdapterTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.shutdown();
    }

    @Test
    void sendsParameterizedCommandWithBearerTokenAndBoundedTimeout() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"result\":[]}"));
        server.start();
        ArcadeDbHttpAdapter adapter = new ArcadeDbHttpAdapter(properties(server.url("/").toString()),
                new ObjectMapper());

        ArcadeDbResponse response = adapter.command("kwiki", "INSERT INTO Entity SET id=:id",
                Map.of("id", "e-1"), ArcadeDbTimeoutKind.BATCH_WRITE);

        assertThat(response.statusCode()).isEqualTo(200);
        var request = server.takeRequest();
        // ArcadeDB 路径只含库名，语言放在请求体里
        assertThat(request.getPath()).isEqualTo("/api/v1/command/kwiki");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer arcade-contract-token");
        assertThat(request.getBody().readUtf8()).contains("\"language\":\"sql\"",
                "INSERT INTO Entity SET id=:id", "e-1");
    }

    @Test
    void cypherQueryExistsAndHealthUseDocumentedEndpoints() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"result\":[]}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"result\":true}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"version\":\"26.9.1\"}"));
        server.start();
        ArcadeDbHttpAdapter adapter = new ArcadeDbHttpAdapter(properties(server.url("/").toString()),
                new ObjectMapper());

        adapter.query("kwiki", "cypher", "MATCH (n) RETURN n", Map.of());
        assertThat(adapter.databaseExists("kwiki")).isTrue();
        adapter.health();

        var query = server.takeRequest();
        assertThat(query.getPath()).isEqualTo("/api/v1/query/kwiki");
        // limit=-1 取消服务端默认 20000 行的静默截断
        assertThat(query.getBody().readUtf8()).contains("\"language\":\"cypher\"", "\"limit\":-1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/exists/kwiki");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/server?mode=basic");
    }

    @Test
    void rejectsUnknownQueryLanguageBeforeExternalCall() throws Exception {
        server = new MockWebServer();
        server.start();
        ArcadeDbHttpAdapter adapter = new ArcadeDbHttpAdapter(properties(server.url("/").toString()),
                new ObjectMapper());

        assertThatThrownBy(() -> adapter.query("kwiki", "gremlin", "g.V()", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void classifiesAuthenticationFailureWithoutExposingToken() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(401).setBody("token=arcade-contract-token"));
        server.start();
        ArcadeDbHttpAdapter adapter = new ArcadeDbHttpAdapter(properties(server.url("/").toString()),
                new ObjectMapper());

        assertThatThrownBy(() -> adapter.health())
                .isInstanceOf(ArcadeDbClientException.class)
                .satisfies(error -> {
                    ArcadeDbClientException exception = (ArcadeDbClientException) error;
                    assertThat(exception.category()).isEqualTo(ArcadeDbFailureCategory.AUTHENTICATION);
                    assertThat(exception.getMessage()).doesNotContain("arcade-contract-token");
                });
    }

    @Test
    void rejectsInvalidDatabasePathBeforeExternalCall() throws Exception {
        server = new MockWebServer();
        server.start();
        ArcadeDbHttpAdapter adapter = new ArcadeDbHttpAdapter(properties(server.url("/").toString()),
                new ObjectMapper());

        assertThatThrownBy(() -> adapter.query("bad/db", "SELECT 1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void classifiesUnresponsiveServerAsTimeout() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                .setResponseCode(200).setBody("{}"));
        server.start();
        ArcadeDbProperties properties = new ArcadeDbProperties(URI.create(server.url("/").toString()),
                "kwiki", "arcade-contract-token",
                Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofMillis(100),
                Duration.ofSeconds(1), 8, new ArcadeDbProperties.Tls(false, true),
                "kwiki_leiden", "24.6");
        ArcadeDbHttpAdapter adapter = new ArcadeDbHttpAdapter(properties, new ObjectMapper());

        assertThatThrownBy(() -> adapter.health())
                .isInstanceOf(ArcadeDbClientException.class)
                .extracting(error -> ((ArcadeDbClientException) error).category())
                .isEqualTo(ArcadeDbFailureCategory.TIMEOUT);
    }

    private static ArcadeDbProperties properties(String endpoint) {
        return new ArcadeDbProperties(URI.create(endpoint), "kwiki", "arcade-contract-token",
                Duration.ofSeconds(3), Duration.ofMillis(1500), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 8,
                new ArcadeDbProperties.Tls(false, true), "kwiki_leiden", "24.6");
    }
}
