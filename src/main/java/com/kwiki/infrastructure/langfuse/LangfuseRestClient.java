package com.kwiki.infrastructure.langfuse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalReportException;
import com.kwiki.eval.EvalReportPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Langfuse 公共 API 客户端（datasets / dataset-items / ingestion / dataset-run-items / traces 删除）。
 * Basic 认证（publicKey:secretKey）；所有请求按 {@code kwiki.langfuse.max-requests-per-minute} 主动限速
 * （Langfuse Cloud 公共 API 默认 100 次/分钟）；429 按服务端 retryAfterSeconds / Retry-After 等待后重试，
 * 最多 {@link #MAX_RATE_LIMIT_ATTEMPTS} 次；5xx 与网络错误按 500ms 起指数退避重试 {@link #MAX_ATTEMPTS} 次；
 * 未配置凭据时 {@link #available()} 为 false，调用方不应再发请求。密钥绝不写日志。
 */
@Component
public class LangfuseRestClient implements EvalReportPort {

    private static final Logger log = LoggerFactory.getLogger(LangfuseRestClient.class);
    static final int MAX_ATTEMPTS = 3;
    static final int FLUSH_THRESHOLD = 20;
    static final int MAX_RATE_LIMIT_ATTEMPTS = 8;
    static final long MAX_RETRY_AFTER_MS = 60_000;
    static final int LIST_PAGE_SIZE = 50;
    static final int DELETE_BATCH = 100;

    private final RestClient http;
    private final ObjectMapper mapper;
    private final LongConsumer sleeper;
    private final LongSupplier nanoTime;
    private final long minIntervalNanos;
    private final Object paceLock = new Object();
    private long nextAllowedNanos = Long.MIN_VALUE;
    private final boolean configured;
    private final List<TraceRecord> buffer = new ArrayList<>();
    private int failedTraces;

    @Autowired
    public LangfuseRestClient(@Value("${kwiki.langfuse.host:https://cloud.langfuse.com}") String host,
                              @Value("${kwiki.langfuse.public-key:}") String publicKey,
                              @Value("${kwiki.langfuse.secret-key:}") String secretKey,
                              ObjectMapper mapper,
                              @Value("${kwiki.langfuse.max-requests-per-minute:90}") int maxRequestsPerMinute) {
        this(host, publicKey, secretKey, mapper, LangfuseRestClient::sleep, System::nanoTime, maxRequestsPerMinute);
    }

    /** 测试用：不限速。 */
    LangfuseRestClient(String host, String publicKey, String secretKey, ObjectMapper mapper,
                       LongConsumer sleeper) {
        this(host, publicKey, secretKey, mapper, sleeper, System::nanoTime, 0);
    }

    LangfuseRestClient(String host, String publicKey, String secretKey, ObjectMapper mapper,
                       LongConsumer sleeper, LongSupplier nanoTime, int maxRequestsPerMinute) {
        this.configured = publicKey != null && !publicKey.isBlank() && secretKey != null && !secretKey.isBlank();
        String token = Base64.getEncoder().encodeToString(
                ((publicKey == null ? "" : publicKey) + ":" + (secretKey == null ? "" : secretKey))
                        .getBytes(StandardCharsets.UTF_8));
        // 显式用 JDK HttpClient：类路径上的 reactor-netty 工厂会在池化连接失效时静默重发
        // POST，导致一次逻辑重试消耗服务端两次响应；JDK 客户端不做静默重试，重试语义完全由本类控制
        java.net.http.HttpClient raw = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        this.http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(raw))
                .baseUrl(host.endsWith("/") ? host.substring(0, host.length() - 1) : host)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.mapper = mapper;
        this.sleeper = sleeper;
        this.nanoTime = nanoTime;
        this.minIntervalNanos = maxRequestsPerMinute <= 0 ? 0 : TimeUnit.MINUTES.toNanos(1) / maxRequestsPerMinute;
    }

    @Override
    public boolean available() {
        return configured;
    }

    @Override
    public void upsertDataset(String name, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", description);
        post("/api/public/datasets", body, true);
    }

    @Override
    public void upsertItem(String datasetName, String itemId, Map<String, Object> input,
                           Map<String, Object> expectedOutput, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasetName", datasetName);
        body.put("id", itemId);
        body.put("input", input);
        body.put("expectedOutput", expectedOutput);
        body.put("metadata", metadata);
        post("/api/public/dataset-items", body, false);
    }

    @Override
    public Map<String, StoredItem> listItems(String datasetName) {
        Map<String, StoredItem> items = new LinkedHashMap<>();
        int totalPages = 1;
        for (int page = 1; page <= totalPages && page <= 1000; page++) {
            int current = page;
            JsonNode response = send("GET /api/public/dataset-items", () -> http.get().uri(builder -> builder
                    .path("/api/public/dataset-items")
                    .queryParam("datasetName", datasetName)
                    .queryParam("page", current)
                    .queryParam("limit", LIST_PAGE_SIZE)
                    .build()), false);
            for (JsonNode item : response.path("data")) {
                items.put(item.path("id").asText(), new StoredItem(
                        item.path("input"), item.path("expectedOutput"), item.path("metadata")));
            }
            totalPages = response.path("meta").path("totalPages").asInt(current);
        }
        return items;
    }

    /** 先按批删除 trace（Langfuse 异步删除），再删 dataset run；dataset run 不存在（404）视为已删除。 */
    @Override
    public void deleteRun(String datasetName, String runName, List<String> traceIds) {
        for (int from = 0; from < traceIds.size(); from += DELETE_BATCH) {
            String payload = payload(Map.of("traceIds",
                    traceIds.subList(from, Math.min(from + DELETE_BATCH, traceIds.size()))));
            send("DELETE /api/public/traces", () -> http.method(HttpMethod.DELETE).uri("/api/public/traces")
                    .body(payload), false);
        }
        try {
            send("DELETE /api/public/datasets/{name}/runs/{run}", () -> http.delete().uri(builder -> builder
                    .pathSegment("api", "public", "datasets", datasetName, "runs", runName).build()), false);
        } catch (EvalReportException e) {
            if (!(e.getCause() instanceof HttpClientErrorException.NotFound)) throw e;
        }
    }

    @Override
    public synchronized void recordTrace(TraceRecord trace) {
        buffer.add(trace);
        if (buffer.size() >= FLUSH_THRESHOLD) sendBuffered();
    }

    @Override
    public synchronized void flush() {
        sendBuffered();
        if (failedTraces > 0) {
            int failed = failedTraces;
            failedTraces = 0;
            throw new EvalReportException(failed + " traces failed to upload to langfuse");
        }
    }

    private void sendBuffered() {
        if (buffer.isEmpty()) return;
        List<TraceRecord> batch = List.copyOf(buffer);
        buffer.clear();
        try {
            JsonNode response = post("/api/public/ingestion", Map.of("batch", ingestionEvents(batch)), false);
            JsonNode errors = response.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                throw new EvalReportException("langfuse ingestion rejected " + errors.size() + " events");
            }
            for (TraceRecord trace : batch) {
                if (trace.datasetItemId() == null) continue;
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("runName", trace.runName());
                link.put("datasetItemId", trace.datasetItemId());
                link.put("traceId", trace.traceId());
                link.put("metadata", trace.runMetadata());
                post("/api/public/dataset-run-items", link, false);
            }
        } catch (RuntimeException e) {
            failedTraces += batch.size();
            log.warn("langfuse upload failed traces={} reason={}", batch.size(), e.getMessage());
        }
    }

    private List<Map<String, Object>> ingestionEvents(List<TraceRecord> batch) {
        String now = Instant.now().toString();
        List<Map<String, Object>> events = new ArrayList<>();
        for (TraceRecord trace : batch) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", trace.traceId());
            body.put("name", trace.name());
            body.put("input", trace.input());
            body.put("output", trace.output());
            body.put("metadata", trace.metadata());
            body.put("tags", trace.tags());
            events.add(event("trace-create", now, body));
            for (SpanRecord span : trace.spans()) {
                Map<String, Object> spanBody = new LinkedHashMap<>();
                spanBody.put("id", span.id());
                spanBody.put("traceId", trace.traceId());
                spanBody.put("name", span.name());
                spanBody.put("startTime", span.start().toString());
                spanBody.put("endTime", span.end().toString());
                spanBody.put("input", span.input());
                spanBody.put("output", span.output());
                events.add(event("span-create", now, spanBody));
            }
            for (ScoreRecord score : trace.scores()) {
                Map<String, Object> scoreBody = new LinkedHashMap<>();
                scoreBody.put("id", UUID.randomUUID().toString());
                scoreBody.put("traceId", trace.traceId());
                scoreBody.put("name", score.name());
                scoreBody.put("value", score.value());
                scoreBody.put("dataType", "NUMERIC");
                if (score.comment() != null) scoreBody.put("comment", score.comment());
                events.add(event("score-create", now, scoreBody));
            }
        }
        return events;
    }

    private static Map<String, Object> event(String type, String timestamp, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("timestamp", timestamp);
        event.put("type", type);
        event.put("body", body);
        return event;
    }

    private JsonNode post(String path, Object body, boolean conflictIsSuccess) {
        String payload = payload(body);
        return send(path, () -> http.post().uri(path).body(payload), conflictIsSuccess);
    }

    private String payload(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new EvalReportException("langfuse payload serialization failed", e);
        }
    }

    /** 统一的限速与重试：429 按服务端提示等待，5xx/网络错误指数退避，二者分别计数。 */
    private JsonNode send(String label, Supplier<RestClient.RequestHeadersSpec<?>> request,
                          boolean conflictIsSuccess) {
        RuntimeException last = null;
        int serverFailures = 0;
        int rateLimited = 0;
        while (true) {
            pace();
            try {
                // 按字节读取再以 UTF-8 解码：响应未声明字符集时 String 转换器会按 ISO-8859-1 解码中文
                byte[] bytes = request.get().retrieve().body(byte[].class);
                String response = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
                return response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
            } catch (HttpClientErrorException e) {
                int status = e.getStatusCode().value();
                if (conflictIsSuccess && status == 409) return mapper.createObjectNode();
                if (status != 429) throw new EvalReportException("langfuse " + label + " rejected with " + status, e);
                last = e;
                if (++rateLimited >= MAX_RATE_LIMIT_ATTEMPTS) break;
                sleeper.accept(retryAfterMillis(e, rateLimited));
            } catch (HttpServerErrorException | ResourceAccessException e) {
                last = e;
                if (++serverFailures >= MAX_ATTEMPTS) break;
                sleeper.accept(500L << (serverFailures - 1));
            } catch (JsonProcessingException e) {
                throw new EvalReportException("langfuse " + label + " returned invalid json", e);
            }
        }
        throw new EvalReportException("langfuse " + label + " failed after " + (serverFailures + rateLimited)
                + " attempts: " + last.getMessage(), last);
    }

    /** 主动限速：相邻请求至少间隔 60s / maxRequestsPerMinute，多线程共享同一节拍。 */
    private void pace() {
        if (minIntervalNanos <= 0) return;
        long waitNanos;
        synchronized (paceLock) {
            long now = nanoTime.getAsLong();
            long start = Math.max(now, nextAllowedNanos);
            nextAllowedNanos = start + minIntervalNanos;
            waitNanos = start - now;
        }
        if (waitNanos > 0) sleeper.accept(TimeUnit.NANOSECONDS.toMillis(waitNanos));
    }

    /** 优先取响应体 details.retryAfterSeconds，其次 Retry-After 头，都没有则指数退避；限制在 1~60 秒。 */
    private long retryAfterMillis(HttpClientErrorException e, int attempt) {
        Long seconds = null;
        try {
            JsonNode hint = mapper.readTree(e.getResponseBodyAsString()).path("details").path("retryAfterSeconds");
            if (hint.isNumber()) seconds = hint.asLong();
        } catch (Exception ignored) {
            // 响应体不是 JSON 时改用响应头
        }
        if (seconds == null && e.getResponseHeaders() != null) {
            String header = e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (header != null) {
                try {
                    seconds = Long.parseLong(header.trim());
                } catch (NumberFormatException ignored) {
                    // HTTP 日期格式的 Retry-After 按退避处理
                }
            }
        }
        long millis = seconds == null ? 1000L << Math.min(attempt - 1, 5) : seconds * 1000;
        return Math.max(1000, Math.min(millis, MAX_RETRY_AFTER_MS));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvalReportException("interrupted while retrying langfuse", e);
        }
    }
}
