package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;

import com.kwiki.rag.orchestration.*;
import com.kwiki.rag.tool.*;

import dev.langchain4j.model.openai.*;

import okhttp3.mockwebserver.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class LowLevelModelContractTest {
    @Test
    void nativeCallsReturnDataAndCanonicalSchemaIsSent() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            String args =
                    ToolRegistry.json(
                            Map.of("queries", List.of("kwiki"), "strategy", "BM25", "topK", 20));
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    ToolRegistry.json(
                                            Map.of(
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "message",
                                                                    Map.of(
                                                                            "role",
                                                                            "assistant",
                                                                            "tool_calls",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "id",
                                                                                            "call-1",
                                                                                            "type",
                                                                                            "function",
                                                                                            "function",
                                                                                            Map.of(
                                                                                                    "name",
                                                                                                    "es_search",
                                                                                                    "arguments",
                                                                                                    args)))),
                                                                    "finish_reason",
                                                                    "tool_calls"))))));
            var model =
                    OpenAiChatModel.builder()
                            .baseUrl(server.url("/v1").toString())
                            .apiKey("test")
                            .modelName("test")
                            .maxRetries(0)
                            .httpClientBuilder(new CancellableModelHttpClient.Builder())
                            .build();
            var adapter = new RetrievalPlannerAdapter(model, new ToolRegistry(), "native");
            var calls = adapter.plan("kwiki", List.of("kwiki"), List.of(), List.of(), "");
            assertThat(calls).hasSize(1);
            assertThat(calls.getFirst().callId()).isEqualTo("call-1");
            var request = ToolRegistry.parse(server.takeRequest().getBody().readUtf8());
            assertThat(adapter.specification().parameters().additionalProperties()).isFalse();
            // Non-strict OpenAI projection omits this keyword; canonical server validation still
            // enforces it.
            assertThat(new ToolRegistry().get("es_search").inputSchema())
                    .contains("additionalProperties");
            assertThat(
                            request.path("tools")
                                    .get(0)
                                    .path("function")
                                    .path("parameters")
                                    .path("required")
                                    .toString())
                    .contains("queries", "strategy", "topK");
        }
    }

    @Test
    void sdkStreamingCompletesWithoutNullOrProtocolText() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                    "data:"
                                        + " {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n"
                                        + "data:"
                                        + " {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                        + "data: [DONE]\n\n"));
            var adapter =
                    new StreamingAnswerLlmAdapter(
                            OpenAiStreamingChatModel.builder()
                                    .baseUrl(server.url("/v1").toString())
                                    .apiKey("test")
                                    .modelName("test")
                                    .httpClientBuilder(new CancellableModelHttpClient.Builder())
                                    .build());
            var flux = adapter.streamAnswer("question");
            assertThat(server.getRequestCount()).isZero();
            assertThat(flux.collectList().block(Duration.ofSeconds(5))).containsExactly("hello");
        }
    }

    @Test
    void disconnectBeforeHeadersClosesTheActualSocket() throws Exception {
        cancellationClosesSocket(false);
    }

    @Test
    void disconnectAfterTokenClosesTheActualSocket() throws Exception {
        cancellationClosesSocket(true);
    }

    private void cancellationClosesSocket(boolean sendToken) throws Exception {
        try (var server = new ServerSocket(0);
                var executor = Executors.newSingleThreadExecutor()) {
            var received = new CountDownLatch(1);
            var token = new CountDownLatch(1);
            Future<Integer> eof =
                    executor.submit(
                            () -> {
                                try (var socket = server.accept()) {
                                    socket.setSoTimeout(5000);
                                    var reader =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            socket.getInputStream(),
                                                            StandardCharsets.UTF_8));
                                    String line;
                                    int length = 0;
                                    while (!(line = reader.readLine()).isEmpty())
                                        if (line.toLowerCase().startsWith("content-length:"))
                                            length = Integer.parseInt(line.split(":")[1].trim());
                                    for (int i = 0; i < length; i++) reader.read();
                                    if (sendToken) {
                                        var out = socket.getOutputStream();
                                        out.write(
                                                ("HTTP/1.1 200 OK\r\n"
                                                     + "Content-Type: text/event-stream\r\n"
                                                     + "Connection: close\r\n\r\n"
                                                     + "data:"
                                                     + " {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n")
                                                        .getBytes(StandardCharsets.UTF_8));
                                        out.flush();
                                    }
                                    received.countDown();
                                    return reader.read();
                                }
                            });
            var adapter =
                    new StreamingAnswerLlmAdapter(
                            OpenAiStreamingChatModel.builder()
                                    .baseUrl("http://127.0.0.1:" + server.getLocalPort() + "/v1")
                                    .apiKey("test")
                                    .modelName("test")
                                    .httpClientBuilder(new CancellableModelHttpClient.Builder())
                                    .build());
            var subscription = adapter.streamAnswer("question").subscribe(v -> token.countDown());
            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            if (sendToken) assertThat(token.await(3, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(eof.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }
}
