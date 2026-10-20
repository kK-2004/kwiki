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
import java.util.function.LongConsumer;

/**
 * Langfuse 公共 API 客户端（datasets / dataset-items / ingestion / dataset-run-items）。
 * Basic 认证（publicKey:secretKey）；5xx、429 与网络错误按 500ms 起指数退避重试 3 次；
 * 未配置凭据时 {@link #available()} 为 false，调用方不应再发请求。密钥绝不写日志。
 */
@Component
public class LangfuseRestClient implements EvalReportPort {

    private static final Logger log = LoggerFactory.getLogger(LangfuseRestClient.class);
    static final int MAX_ATTEMPTS = 3;
    static final int FLUSH_THRESHOLD = 20;

    private final RestClient http;
    private final ObjectMapper mapper;
    private final LongConsumer sleeper;
    private final boolean configured;
    private final List<TraceRecord> buffer = new ArrayList<>();
    private int failedTraces;

    @Autowired
    public LangfuseRestClient(@Value("${kwiki.langfuse.host:https://cloud.langfuse.com}") String host,
                              @Value("${kwiki.langfuse.public-key:}") String publicKey,
                              @Value("${kwiki.langfuse.secret-key:}") String secretKey,
                              ObjectMapper mapper) {
        this(host, publicKey, secretKey, mapper, LangfuseRestClient::sleep);
    }

    LangfuseRestClient(String host, String publicKey, String secretKey, ObjectMapper mapper,
                       LongConsumer sleeper) {
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
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new EvalReportException("langfuse payload serialization failed", e);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = http.post().uri(path).body(payload).retrieve().body(String.class);
                return response == null || response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
            } catch (HttpClientErrorException e) {
                int status = e.getStatusCode().value();
                if (conflictIsSuccess && status == 409) return mapper.createObjectNode();
                if (status != 429) throw new EvalReportException("langfuse " + path + " rejected with " + status, e);
                last = e;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                last = e;
            } catch (JsonProcessingException e) {
                throw new EvalReportException("langfuse " + path + " returned invalid json", e);
            }
            if (attempt < MAX_ATTEMPTS) sleeper.accept(500L << (attempt - 1));
        }
        throw new EvalReportException("langfuse " + path + " failed after " + MAX_ATTEMPTS + " attempts: "
                + last.getMessage(), last);
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
