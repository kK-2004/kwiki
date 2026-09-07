package com.kwiki.rag.answer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.security.*;
import com.kwiki.testutil.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import reactor.core.publisher.Flux;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WikiMockBeans.class)
class ChatHttpWireTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @MockitoBean AgenticWorkflowPort workflow;

    @Test
    void anonymousStreamStillRequiresAuthentication() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/v1/chat/stream"))
                                    .header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"query\":\"question\"}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            verifyNoInteractions(workflow);
        }
    }

    @Test
    void httpDataIsFlatJsonWithExactlyOneSseEnvelope() throws Exception {
        when(workflow.answer(any(), anyString()))
                .thenReturn(
                        Flux.just(
                                ChatStreamEvent.of("token", 1, "request", Map.of("text", "你好")),
                                ChatStreamEvent.of("done", 2, "request", Map.of())));
        try (var client = HttpClient.newHttpClient()) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/v1/chat/stream"))
                                    .header(
                                            "Authorization",
                                            "Bearer "
                                                    + tokens.issue(
                                                            new CurrentUser(1L, "root", true)))
                                    .header("Content-Type", "application/json")
                                    .timeout(Duration.ofSeconds(5))
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"query\":\"question\"}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var data =
                    response.body()
                            .lines()
                            .filter(l -> l.startsWith("data:"))
                            .map(l -> l.substring(5).strip())
                            .toList();
            assertThat(data).hasSize(2);
            var json = ToolRegistry.parse(data.getFirst());
            assertThat(json.path("seq").asInt()).isEqualTo(1);
            assertThat(json.path("text").asText()).isEqualTo("你好");
            assertThat(json.has("payload")).isFalse();
            assertThat(response.body()).doesNotContain("data:event:", "data: event:");
        }
    }
}
