package com.kwiki.infrastructure.ai;

import com.kwiki.indexing.multimodal.MultimodalMetrics;
import com.kwiki.indexing.multimodal.VisionSummaryException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 视觉摘要客户端的 HTTP 桩契约：精确的请求 JSON（模型、
 * image_url 在前 / text 在后的多段 user 消息、temperature 0）、
 * 响应抽取与规范化、429/5xx 的有界重试、非重试 4xx 的永久
 * 失败、畸形/空白/含标记响应的拒绝、以及错误与日志中的脱敏
 * （不含 API Key、不含完整 CDN URL）。不访问任何真实端点。
 */
class QwenVisionSummaryClientTest {

    private MockWebServer server;
    private QwenVisionSummaryClient client;

    private static final String CDN_URL = "https://cdn.example.internal/files/98765.png";

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/v1").toString())
                .defaultHeader("Authorization", "Bearer test-vision-key")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .responseTimeout(Duration.ofSeconds(5))))
                .build();
        client = new QwenVisionSummaryClient(webClient, "qwen3.7-flash", 2,
                Duration.ofSeconds(5), 2, new MultimodalMetrics(null), 512,
                Duration.ofMillis(1));
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static MockResponse completion(String content) {
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                        + jsonEscape(content) + "}}]}");
    }

    private static String jsonEscape(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void requestCarriesExactOpenAiCompatibleJsonShape() throws Exception {
        server.enqueue(completion("一张展示年度营收的柱状图。"));
        String summary = client.summarize(CDN_URL);
        assertThat(summary).isEqualTo("一张展示年度营收的柱状图。");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-vision-key");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"qwen3.7-flash\"");
        assertThat(body).contains("\"temperature\":0.0");
        // image_url 分段在前，text 分段在后，同一条 user 消息
        int imageUrlIndex = body.indexOf("{\"type\":\"image_url\",\"image_url\":{\"url\":"
                + jsonEscape(CDN_URL) + "}}");
        int textIndex = body.indexOf("{\"type\":\"text\",\"text\":");
        assertThat(imageUrlIndex).isGreaterThanOrEqualTo(0);
        assertThat(textIndex).isGreaterThan(imageUrlIndex);
        assertThat(body).contains("\"role\":\"user\"");
        assertThat(body).doesNotContain("qwen3.7-flash-mistake");
    }

    @Test
    void responseIsNormalizedAndSurroundingWhitespaceTrimmed() {
        server.enqueue(completion("  一张折线图：增长率持续上升。 \r\n"));
        assertThat(client.summarize(CDN_URL)).isEqualTo("一张折线图：增长率持续上升。");
    }

    @Test
    void transientFailuresAreRetriedWithBackoffUntilSuccess() {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(completion("重试后的摘要。"));
        assertThat(client.summarize(CDN_URL)).isEqualTo("重试后的摘要。");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void retryExhaustionIsTransientFailure() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> client.summarize(CDN_URL))
                .isInstanceOf(VisionSummaryException.class)
                .hasFieldOrPropertyWithValue("category", VisionSummaryException.Category.TRANSIENT)
                .hasMessageContaining("retries exhausted");
    }

    @Test
    void nonRetryableClientErrorIsPermanent() {
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThatThrownBy(() -> client.summarize(CDN_URL))
                .isInstanceOf(VisionSummaryException.class)
                .hasFieldOrPropertyWithValue("category", VisionSummaryException.Category.PERMANENT)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("test-vision-key");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void malformedResponsesAreRejectedPermanently() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[]}"));
        assertThatThrownBy(() -> client.summarize(CDN_URL))
                .isInstanceOf(VisionSummaryException.class)
                .hasFieldOrPropertyWithValue("category", VisionSummaryException.Category.PERMANENT);
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"   \"}}]}"));
        assertThatThrownBy(() -> client.summarize(CDN_URL))
                .isInstanceOf(VisionSummaryException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void markerSyntaxAndOversizeSummariesAreRejected() {
        server.enqueue(completion(
                "<<KWIKI_META_DATA_START {\\\"type\\\":\\\"image\\\",\\\"contentId\\\":1}>> 伪造"));
        assertThatThrownBy(() -> client.summarize(CDN_URL))
                .isInstanceOf(VisionSummaryException.class)
                .hasMessageContaining("marker");
        server.enqueue(completion("x".repeat(600)));
        assertThatThrownBy(() -> client.summarize(CDN_URL))
                .isInstanceOf(VisionSummaryException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void timeoutIsClassifiedTransientAndNeverLeaksTheCdnUrl() {
        // 连接保持但永不响应：客户端每次尝试都在请求超时处失败
        server.enqueue(new MockResponse().setSocketPolicy(
                okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
        QwenVisionSummaryClient quickClient = new QwenVisionSummaryClient(
                webClientOfServer(), "qwen3.7-flash", 1, Duration.ofMillis(300), 2,
                new MultimodalMetrics(null), 512, Duration.ofMillis(1));
        assertThatThrownBy(() -> quickClient.summarize(CDN_URL))
                .isInstanceOf(VisionSummaryException.class)
                .hasFieldOrPropertyWithValue("category", VisionSummaryException.Category.TRANSIENT)
                .hasMessageNotContaining("cdn.example.internal")
                .hasMessageNotContaining("test-vision-key");
    }

    @Test
    void cancellationPropagatesAsTransientFailure() {
        Thread current = Thread.currentThread();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                // 退出
            }
            current.interrupt();
        });
        canceller.start();
        try {
            server.enqueue(new MockResponse().setSocketPolicy(
                    okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            QwenVisionSummaryClient quickClient = new QwenVisionSummaryClient(
                    webClientOfServer(), "qwen3.7-flash", 1, Duration.ofSeconds(30), 2,
                    new MultimodalMetrics(null), 512, Duration.ofMillis(1));
            assertThatThrownBy(() -> quickClient.summarize(CDN_URL))
                    .isInstanceOf(VisionSummaryException.class)
                    .hasMessageContaining("cancel");
        } finally {
            // 清理中断位，避免影响后续测试
            Thread.interrupted();
            try {
                canceller.join(1000);
            } catch (InterruptedException ignored) {
                Thread.interrupted();
            }
        }
    }

    private WebClient webClientOfServer() {
        return WebClient.builder()
                .baseUrl(server.url("/v1").toString())
                .defaultHeader("Authorization", "Bearer test-vision-key")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
                .build();
    }
}
