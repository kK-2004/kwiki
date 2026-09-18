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
                throw new HttpException(response.statusCode(), "model request rejected");
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
                                        response.statusCode(), "model request rejected");
                            listener.onOpen(
                                    SuccessfulHttpResponse.builder()
                                            .statusCode(response.statusCode())
                                            .headers(response.headers().map())
                                            .build());
                            parser.parse(input, listener);
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
        try {
            path = URI.create(request.url()).getPath();
        } catch (Exception ignored) {
            path = "unknown";
        }
        String requestBody = request.body();
        boolean structuredOutput = requestBody != null && requestBody.contains("response_format");
        String structuredOutputMode = requestBody != null && requestBody.contains("json_schema")
                ? "json-schema" : structuredOutput ? "json-object" : "none";
        String thinkingMode = requestBody != null && requestBody.matches(
                "(?s).*\\\"enable_thinking\\\"\\s*:\\s*false.*")
                ? "disabled" : requestBody != null && requestBody.matches(
                        "(?s).*\\\"enable_thinking\\\"\\s*:\\s*true.*")
                        ? "enabled" : "provider-default";
        Object[] fields = {
                operation, run == null ? "-" : run.requestId, request.method(), path,
                statusCode == null ? "-" : statusCode,
                structuredOutput, structuredOutputMode, thinkingMode,
                terminationReason == null ? "-" : terminationReason,
                error == null ? "unknown" : error.getClass().getSimpleName(),
                message == null ? "-" : message
        };
        String template = "model transport failed operation={} requestId={} method={} path={} "
                + "statusCode={} structuredOutput={} structuredOutputMode={} thinkingMode={} "
                + "terminationReason={} errorClass={} error={}";
        if ("cancelled".equals(terminationReason)) {
            log.debug(template, fields);
        } else {
            log.warn(template, fields);
        }
    }

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
