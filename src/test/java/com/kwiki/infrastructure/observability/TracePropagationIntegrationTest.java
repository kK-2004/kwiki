package com.kwiki.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.security.CurrentUser;
import com.kwiki.security.JwtTokenService;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.testutil.WikiMockBeans;

import io.micrometer.tracing.Tracer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Real sockets verify server extraction, MDC correlation and both AI clients' injection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import({WikiMockBeans.class, TracePropagationIntegrationTest.ProbeEndpoint.class})
class TracePropagationIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final MockWebServer UPSTREAM = startUpstream();

    private static MockWebServer startUpstream() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start tracing test upstream", ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
        registry.add("kwiki.answer-llm.base-url", () -> UPSTREAM.url("/").toString());
        registry.add("kwiki.qwen-embedding.base-url", () -> UPSTREAM.url("/").toString());
    }

    @AfterAll
    static void closeUpstream() throws IOException {
        UPSTREAM.shutdown();
    }

    @LocalServerPort int port;

    @Autowired JwtTokenService tokens;

    @ParameterizedTest
    @ValueSource(strings = {"answer", "embedding"})
    void incomingTraceContinuesThroughServerAndAiWebClient(String provider) throws Exception {
        UPSTREAM.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                provider.equals("answer")
                                        ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                                        : "ok"));
        // A plain JDK client leaves the supplied parent untouched by client instrumentation.
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder()
                                    .uri(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/v1/trace-probe?provider="
                                                            + provider))
                                    .timeout(Duration.ofSeconds(10))
                                    .header(
                                            "traceparent",
                                            "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01")
                                    .header(
                                            "Authorization",
                                            "Bearer "
                                                    + tokens.issue(
                                                            new CurrentUser(
                                                                    7L, "trace-test", false)))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue(TraceIdResponseHeaderFilter.HEADER))
                    .contains(TRACE_ID);
            String[] context = response.body().split(":");
            assertThat(context).hasSize(3);
            assertThat(context[0]).isEqualTo(TRACE_ID);
            assertThat(context[1]).isEqualTo(TRACE_ID);
            assertThat(context[2]).matches("[0-9a-f]{16}").isNotEqualTo(PARENT_SPAN_ID);

            RecordedRequest outgoing = UPSTREAM.takeRequest(5, TimeUnit.SECONDS);
            assertThat(outgoing).isNotNull();
            assertThat(outgoing.getPath())
                    .isEqualTo(provider.equals("answer") ? "/chat/completions" : "/trace-probe");
            String traceparent = outgoing.getHeader("traceparent");
            assertThat(traceparent).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
            assertThat(traceparent.split("-")[2])
                    .isNotEqualTo(PARENT_SPAN_ID)
                    .isNotEqualTo(context[2]);
        }
    }

    @TestComponent
    @RestController
    static class ProbeEndpoint {
        private final dev.langchain4j.model.chat.ChatModel answer;
        private final WebClient embedding;
        private final Tracer tracer;

        @Autowired
        ProbeEndpoint(
                dev.langchain4j.model.chat.ChatModel answer,
                @Qualifier("kwikiEmbeddingWebClient") WebClient embedding,
                Tracer tracer) {
            this.answer = answer;
            this.embedding = embedding;
            this.tracer = tracer;
        }

        @GetMapping("/api/v1/trace-probe")
        String probe(@RequestParam String provider) {
            String context =
                    tracer.currentSpan().context().traceId()
                            + ":"
                            + MDC.get("traceId")
                            + ":"
                            + tracer.currentSpan().context().spanId();
            if (provider.equals("answer")) answer.chat("trace probe");
            else
                embedding
                        .get()
                        .uri("/trace-probe")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
            return context;
        }
    }
}
