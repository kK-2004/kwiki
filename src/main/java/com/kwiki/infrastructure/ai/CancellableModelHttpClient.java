package com.kwiki.infrastructure.ai;

import com.kwiki.rag.orchestration.*;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 仅负责传输（transport）：完成结果的 JSON 与 SSE 解析由 SDK 负责。 */
public final class CancellableModelHttpClient implements HttpClient {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CancellableModelHttpClient.class);
    private static final ExecutorService STREAMS =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("model-http-", 0).factory());
    private static final Semaphore PERMITS = new Semaphore(64);
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("model-deadline").factory());
    private final java.net.http.HttpClient client;
    private final Duration timeout;
    private final io.micrometer.tracing.Tracer tracer;
    private final io.micrometer.tracing.propagation.Propagator propagator;

    public CancellableModelHttpClient(Duration connect, Duration timeout) {
        this(connect, timeout, null, null);
    }

    private CancellableModelHttpClient(
            Duration connect,
            Duration timeout,
            io.micrometer.tracing.Tracer tracer,
            io.micrometer.tracing.propagation.Propagator propagator) {
        this.client = java.net.http.HttpClient.newBuilder().connectTimeout(connect).build();
        this.timeout = timeout;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    private java.net.http.HttpRequest request(
            HttpRequest request, RunContext run, io.micrometer.tracing.Span span) {
        var builder =
                java.net.http.HttpRequest.newBuilder(URI.create(request.url()))
                        .timeout(run == null ? timeout : run.timeout(timeout));
        request.headers()
                .forEach((key, values) -> values.forEach(value -> builder.header(key, value)));
        if (run != null) run.traceHeaders.forEach(builder::setHeader);
        if (span != null && propagator != null)
            propagator.inject(
                    span.context(),
                    builder,
                    (carrier, key, value) -> carrier.setHeader(key, value));
        return builder.method(
                        request.method().name(),
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                                request.body() == null ? "" : request.body()))
                .build();
    }

    private io.micrometer.tracing.Span span(RunContext run) {
        if (tracer == null) return null;
        return run != null && propagator != null && !run.traceHeaders.isEmpty()
                ? propagator
                        .extract(run.traceHeaders, (carrier, key) -> carrier.get(key))
                        .name("kwiki.model.http")
                        .start()
                : tracer.nextSpan().name("kwiki.model.http").start();
    }

    private <T> CompletableFuture<HttpResponse<T>> send(
            HttpRequest request,
            RunContext run,
            io.micrometer.tracing.Span span,
            HttpResponse.BodyHandler<T> handler) {
        try {
            return client.sendAsync(request(request, run, span), handler);
        } catch (RuntimeException e) {
            if (span != null) span.end();
            PERMITS.release();
            throw e;
        }
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        RunContext run = RunContext.current();
        if (!PERMITS.tryAcquire()) throw new RunFailure("model-busy");
        var span = span(run);
        var future = send(request, run, span, HttpResponse.BodyHandlers.ofString());
        try (AutoCloseable registration =
                run == null ? () -> {} : run.onCancel(() -> future.cancel(true))) {
            var response =
                    future.get(
                            (run == null ? timeout : run.timeout(timeout)).toMillis(),
                            TimeUnit.MILLISECONDS);
            if (response.statusCode() >= 300)
                throw new HttpException(response.statusCode(), rejection(response.body()));
            return SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers().map())
                    .body(response.body())
                    .build();
        } catch (HttpException e) {
            logFailure("request", request, run, e, null);
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = rootCause(e);
            logFailure("request", request, run, cause, null);
            throw new RunFailure(
                    e instanceof TimeoutException ? "timeout" : "model-request-failed", cause);
        } finally {
            future.cancel(true);
            if (span != null) span.end();
            PERMITS.release();
        }
    }

    @Override
    public void execute(
            HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        RunContext run = RunContext.current();
        long timeoutMillis = (run == null ? timeout : run.timeout(timeout)).toMillis();
        if (!PERMITS.tryAcquire()) {
            listener.onError(new RunFailure("model-busy"));
            return;
        }
        AtomicReference<InputStream> body = new AtomicReference<>();
        var transportCancelled = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<String> cancellationReason = new AtomicReference<>();
        var span = span(run);
        var future = send(request, run, span, HttpResponse.BodyHandlers.ofInputStream());
        Runnable cancel =
                () -> {
                    transportCancelled.set(true);
                    future.cancel(true);
                    var input = body.get();
                    if (input != null)
                        try {
                            input.close();
                        } catch (IOException ignored) {
                        }
                };
        AutoCloseable registration = run == null
                ? () -> {}
                : run.onCancel(() -> {
                    cancellationReason.compareAndSet(null, "cancelled");
                    cancel.run();
                });
        var deadline = TIMER.schedule(() -> {
            cancellationReason.compareAndSet(null, "timeout");
            cancel.run();
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        STREAMS.submit(
                () -> {
                    try (registration) {
                        var response = future.get();
                        body.set(response.body());
                        if (transportCancelled.get()) throw new RunFailure("cancelled");
                        if (run != null) run.check();
                        try (var input = response.body()) {
                            if (response.statusCode() >= 300)
                                throw new HttpException(
                                        response.statusCode(), rejection(readBounded(input)));
                            listener.onOpen(
                                    SuccessfulHttpResponse.builder()
                                            .statusCode(response.statusCode())
                                            .headers(response.headers().map())
                                            .build());
                            parser.parse(input, withFailureReason(listener, cancellationReason,
                                    error -> logFailure("stream", request, run, error, cancellationReason.get())));
                            listener.onClose();
                        }
                    } catch (Exception e) {
                        Throwable cause = rootCause(e);
                        String reason = cancellationReason.get();
                        logFailure("stream", request, run, cause, reason);
                        String code = reason == null ? "model-stream-failed" : reason;
                        listener.onError(new RunFailure(code, cause));
                    } finally {
                        cancel.run();
                        deadline.cancel(false);
                        if (span != null) span.end();
                        PERMITS.release();
                    }
                });
    }

    /**
     * SSE 解析器读流失败时会自行回调 onError 并只交出原始 IOException（如主动关闭流导致的 "closed"），
     * 不经过本类的统一包装。这里按流的实际状态补上原因码：主动取消 / 超时为 cancelled / timeout，
     * 否则为 model-stream-failed，使日志与上层能区分取消、超时与断流。
     */
    static ServerSentEventListener withFailureReason(ServerSentEventListener delegate,
                                                     AtomicReference<String> reason,
                                                     java.util.function.Consumer<Throwable> log) {
        return new ServerSentEventListener() {
            @Override
            public void onOpen(SuccessfulHttpResponse response) {
                delegate.onOpen(response);
            }

            @Override
            public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                delegate.onEvent(event, context);
            }

            @Override
            public void onEvent(ServerSentEvent event) {
                delegate.onEvent(event);
            }

            @Override
            public void onError(Throwable error) {
                if (error instanceof RunFailure) {
                    delegate.onError(error);
                    return;
                }
                log.accept(error);
                String code = reason.get();
                delegate.onError(new RunFailure(code == null ? "model-stream-failed" : code, error));
            }

            @Override
            public void onClose() {
                delegate.onClose();
            }
        };
    }

    static final int MAX_ERROR_BODY_BYTES = 2048;
    static final int MAX_ERROR_REASON_CHARS = 500;

    /**
     * 拒绝原因：保留 provider 的错误响应（脱敏、截断）供服务端日志定位，例如不支持的参数、欠费、鉴权失败。
     * 对用户仍只暴露稳定错误码（RunFailure），该信息不进入响应体。
     */
    static String rejection(String body) {
        if (body == null || body.isBlank()) return "model request rejected";
        String reason = body.replaceAll("\\s+", " ").strip();
        reason = com.kwiki.infrastructure.observability.SecretRedaction.redact(reason)
                .replaceAll("\\b(?:sk|pk|ak)-[A-Za-z0-9_\\-]{8,}",
                        com.kwiki.infrastructure.observability.SecretRedaction.REDACTED);
        if (reason.length() > MAX_ERROR_REASON_CHARS) reason = reason.substring(0, MAX_ERROR_REASON_CHARS) + "…";
        return "model request rejected: " + reason;
    }

    private static String readBounded(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_ERROR_BODY_BYTES);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    private static void logFailure(
            String operation, HttpRequest request, RunContext run, Throwable error,
            String terminationReason) {
        Integer statusCode = null;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpException http) {
                statusCode = http.statusCode();
                break;
            }
        }
        String message = com.kwiki.infrastructure.observability.SecretRedaction.redact(
                error == null ? null : error.getMessage());
        if (message != null && message.length() > 500) {
            message = message.substring(0, 500) + "…[truncated]";
        }
        String path;
        String host;
        try {
            URI uri = URI.create(request.url());
            path = uri.getPath();
            host = uri.getHost();
        } catch (Exception ignored) {
            path = "unknown";
            host = "unknown";
        }
        String requestBody = request.body();
        // 模型名取自请求体，便于区分各用途分配到的 provider / 模型
        java.util.regex.Matcher modelMatch = requestBody == null ? null
                : MODEL_FIELD.matcher(requestBody);
        String model = modelMatch != null && modelMatch.find() ? modelMatch.group(1) : "-";
        boolean structuredOutput = requestBody != null && requestBody.contains("response_format");
        String structuredOutputMode = requestBody != null && requestBody.contains("json_schema")
                ? "json-schema" : structuredOutput ? "json-object" : "none";
        String thinkingMode = requestBody != null && requestBody.matches(
                "(?s).*\\\"enable_thinking\\\"\\s*:\\s*false.*")
                ? "disabled" : requestBody != null && requestBody.matches(
                        "(?s).*\\\"enable_thinking\\\"\\s*:\\s*true.*")
                        ? "enabled" : "provider-default";
        Object[] fields = {
                operation, run == null ? "-" : run.requestId, request.method(), host, path, model,
                statusCode == null ? "-" : statusCode,
                structuredOutput, structuredOutputMode, thinkingMode,
                terminationReason == null ? "-" : terminationReason,
                error == null ? "unknown" : error.getClass().getSimpleName(),
                message == null ? "-" : message
        };
        String template = "model transport failed operation={} requestId={} method={} host={} path={} model={} "
                + "statusCode={} structuredOutput={} structuredOutputMode={} thinkingMode={} "
                + "terminationReason={} errorClass={} error={}";
        boolean cancelled = "cancelled".equals(terminationReason) || isInterruption(error);
        boolean jsonSchemaUnsupported = statusCode != null && statusCode == 400
                && "json-schema".equals(structuredOutputMode)
                && message != null && message.toLowerCase().contains("response_format");
        if (cancelled) {
            log.debug(template, fields);
        } else if (jsonSchemaUnsupported) {
            // 预期内：调用方会回退到 json_object
            log.info(template, fields);
        } else {
            log.warn(template, fields);
        }
    }

    private static boolean isInterruption(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof InterruptedException) return true;
            if (current instanceof RunFailure failure && "cancelled".equals(failure.getMessage())) return true;
        }
        return false;
    }

    private static final java.util.regex.Pattern MODEL_FIELD =
            java.util.regex.Pattern.compile("\"model\"\\s*:\\s*\"([^\"]{1,200})\"");

    public static final class Builder implements HttpClientBuilder {
        private Duration connect = Duration.ofSeconds(5), read = Duration.ofSeconds(120);
        private io.micrometer.tracing.Tracer tracer;
        private io.micrometer.tracing.propagation.Propagator propagator;

        public Builder tracing(
                io.micrometer.tracing.Tracer tracer,
                io.micrometer.tracing.propagation.Propagator propagator) {
            this.tracer = tracer;
            this.propagator = propagator;
            return this;
        }

        public Duration connectTimeout() {
            return connect;
        }

        public Builder connectTimeout(Duration value) {
            if (value != null) connect = value;
            return this;
        }

        public Duration readTimeout() {
            return read;
        }

        public Builder readTimeout(Duration value) {
            if (value != null) read = value;
            return this;
        }

        public HttpClient build() {
            return new CancellableModelHttpClient(connect, read, tracer, propagator);
        }
    }
}
