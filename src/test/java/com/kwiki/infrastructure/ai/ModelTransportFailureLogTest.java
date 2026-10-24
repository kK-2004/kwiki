package com.kwiki.infrastructure.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kwiki.rag.orchestration.RunFailure;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 传输失败日志带上实际等待时长与生效超时，区分「超时设太短」与「上游卡住 / 立即拒绝」。 */
class ModelTransportFailureLogTest {

    private static final Pattern ELAPSED = Pattern.compile("elapsedMs=(\\d+) timeoutMs=(\\d+)");

    private final Logger logger = (Logger) LoggerFactory.getLogger(CancellableModelHttpClient.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private MockWebServer server;

    @BeforeEach
    void attach() throws Exception {
        appender.start();
        logger.addAppender(appender);
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void detach() throws Exception {
        logger.detachAppender(appender);
        server.shutdown();
    }

    private HttpRequest chatRequest() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(server.url("/v1/chat/completions").toString())
                .addHeader("Content-Type", "application/json")
                .body("{\"model\":\"deepseek/deepseek-v4.1-flash\",\"messages\":[]}")
                .build();
    }

    /** 唯一一条 WARN 传输失败日志里的 elapsedMs / timeoutMs。 */
    private long[] loggedTiming() {
        ILoggingEvent event = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().startsWith("model transport failed"))
                .reduce((first, second) -> {
                    throw new AssertionError("期望只有一条传输失败日志");
                })
                .orElseThrow(() -> new AssertionError("没有传输失败日志"));
        Matcher matcher = ELAPSED.matcher(event.getFormattedMessage());
        assertThat(matcher.find()).as(event.getFormattedMessage()).isTrue();
        // 字段顺序：terminationReason 之后、errorClass 之前
        assertThat(event.getFormattedMessage()).containsPattern("terminationReason=\\S+ elapsedMs=\\d+ timeoutMs=\\d+ errorClass=");
        return new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
    }

    private String lastWarn() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void jdkRequestTimeoutIsRecognisedAsATimeout() {
        assertThat(CancellableModelHttpClient.isTimeout(
                new java.net.http.HttpTimeoutException("request timed out"))).isTrue();
        assertThat(CancellableModelHttpClient.isTimeout(new java.util.concurrent.ExecutionException(
                new java.net.http.HttpConnectTimeoutException("connect timed out")))).isTrue();
        assertThat(CancellableModelHttpClient.isTimeout(new java.io.IOException("reset"))).isFalse();
    }

    @Test
    void timedOutRequestLogsHowLongItWaitedAndTheEffectiveTimeout() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var client = new CancellableModelHttpClient(Duration.ofSeconds(2), Duration.ofMillis(300));

        // 不论先触发的是 JDK 请求超时还是本地等待超时，都归为 timeout
        assertThatThrownBy(() -> client.execute(chatRequest()))
                .isInstanceOf(RunFailure.class)
                .hasMessage("timeout");

        long[] timing = loggedTiming();
        assertThat(lastWarn()).contains("terminationReason=timeout");
        assertThat(timing[1]).isEqualTo(300);
        // 等满了超时：等待时长不短于超时（留出调度误差），也不会长到离谱
        assertThat(timing[0]).isBetween(250L, 5_000L);
    }

    @Test
    void immediateRejectionShowsAShortWaitAgainstALongTimeout() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":\"upstream busy\"}"));
        var client = new CancellableModelHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(60));

        assertThatThrownBy(() -> client.execute(chatRequest()));

        long[] timing = loggedTiming();
        assertThat(timing[1]).isEqualTo(60_000);
        assertThat(timing[0]).isLessThan(60_000);
    }

    @Test
    void timedOutStreamLogsTheSameTimingFields() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var client = new CancellableModelHttpClient(Duration.ofSeconds(2), Duration.ofMillis(300));
        CompletableFuture<Throwable> failed = new CompletableFuture<>();

        client.execute(chatRequest(), new DefaultServerSentEventParser(), new ServerSentEventListener() {
            @Override
            public void onEvent(ServerSentEvent event) {
            }

            @Override
            public void onError(Throwable error) {
                failed.complete(error);
            }
        });

        Throwable error = failed.get(10, TimeUnit.SECONDS);
        assertThat(error).isInstanceOf(RunFailure.class).hasMessage("timeout");
        long[] timing = loggedTiming();
        assertThat(lastWarn()).contains("terminationReason=timeout");
        assertThat(timing[1]).isEqualTo(300);
        assertThat(timing[0]).isBetween(250L, 5_000L);
    }
}
