package com.kwiki.infrastructure.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalReportException;
import com.kwiki.eval.EvalReportPort;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseRestClientTest {

    final ObjectMapper mapper = new ObjectMapper();
    final List<Long> sleeps = new CopyOnWriteArrayList<>();
    MockWebServer server;
    LangfuseRestClient client;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new LangfuseRestClient(server.url("/").toString(), "pk-lf-test", "sk-lf-test", mapper, sleeps::add);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    JsonNode body(RecordedRequest request) throws Exception {
        return mapper.readTree(request.getBody().readString(StandardCharsets.UTF_8));
    }

    static EvalReportPort.TraceRecord trace() {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        return new EvalReportPort.TraceRecord("trace-1", "eval:db-1", Map.of("query", "问题"),
                Map.of("answer", "回答"), Map.of("category", "事实性问题"), List.of("eval"),
                List.of(new EvalReportPort.SpanRecord("span-1", "bm25@50", now, now,
                        Map.of("query", "问题"), Map.of("matched", List.of(0)))),
                List.of(new EvalReportPort.ScoreRecord("coverage@answer", 0.5, null)),
                "kwiki-rag-50q:db-1", "50q-run", Map.of("notes", "baseline"));
    }

    @Test
    void unconfiguredClientIsUnavailable() {
        assertThat(new LangfuseRestClient("https://cloud.langfuse.com", "", "", mapper, sleeps::add).available())
                .isFalse();
        assertThat(client.available()).isTrue();
    }

    @Test
    void datasetConflictIsTreatedAsExisting() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409).setBody("{\"message\":\"exists\"}"));

        client.upsertDataset("kwiki-rag-50q", "desc");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/public/datasets");
        assertThat(request.getHeader("Authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("pk-lf-test:sk-lf-test".getBytes(StandardCharsets.UTF_8)));
        assertThat(body(request).get("name").asText()).isEqualTo("kwiki-rag-50q");
    }

    @Test
    void itemUpsertRetriesServerErrorsWithBackoff() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"unavailable\"}"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"unavailable\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.upsertItem("kwiki-rag-50q", "kwiki-rag-50q:db-1", Map.of("query", "问题"),
                Map.of("keywords", List.of(List.of("甲"))), Map.of("tier", 50));

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(sleeps).containsExactly(500L, 1000L);
        JsonNode item = body(server.takeRequest());
        assertThat(item.get("id").asText()).isEqualTo("kwiki-rag-50q:db-1");
        assertThat(item.get("datasetName").asText()).isEqualTo("kwiki-rag-50q");
        assertThat(item.get("expectedOutput").get("keywords").get(0).get(0).asText()).isEqualTo("甲");
    }

    @Test
    void flushSendsIngestionBatchThenRunItems() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(207).setBody("{\"successes\":[],\"errors\":[]}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.recordTrace(trace());
        client.flush();

        RecordedRequest ingestion = server.takeRequest();
        assertThat(ingestion.getPath()).isEqualTo("/api/public/ingestion");
        JsonNode batch = body(ingestion).get("batch");
        assertThat(batch).extracting(node -> node.get("type").asText())
                .containsExactly("trace-create", "span-create", "score-create");
        assertThat(batch.get(1).get("body").get("traceId").asText()).isEqualTo("trace-1");
        assertThat(batch.get(2).get("body").get("value").asDouble()).isEqualTo(0.5);
        RecordedRequest runItem = server.takeRequest();
        assertThat(runItem.getPath()).isEqualTo("/api/public/dataset-run-items");
        JsonNode link = body(runItem);
        assertThat(link.get("runName").asText()).isEqualTo("50q-run");
        assertThat(link.get("datasetItemId").asText()).isEqualTo("kwiki-rag-50q:db-1");
        assertThat(link.get("traceId").asText()).isEqualTo("trace-1");
    }

    @Test
    void flushReportsFailureAfterRetriesAreExhausted() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"unavailable\"}"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"unavailable\"}"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"unavailable\"}"));

        client.recordTrace(trace());

        assertThatThrownBy(client::flush).isInstanceOf(EvalReportException.class)
                .hasMessageContaining("1 traces");
    }

    @Test
    void ingestionPartialErrorsFailTheBatch() {
        server.enqueue(new MockResponse().setResponseCode(207)
                .setBody("{\"successes\":[],\"errors\":[{\"id\":\"x\",\"status\":400}]}"));

        client.recordTrace(trace());

        assertThatThrownBy(client::flush).isInstanceOf(EvalReportException.class);
    }
    @Test
    void rateLimitedRequestWaitsForRetryAfterThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody(
                "{\"message\":\"Rate limit exceeded\",\"code\":\"rate_limited\",\"details\":{\"retryAfterSeconds\":2}}"));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "3"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.upsertItem("kwiki-rag-50q", "kwiki-rag-50q:db-1", Map.of("query", "问题"), Map.of(), Map.of());

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(sleeps).containsExactly(2000L, 3000L);
    }

    @Test
    void rateLimitIsRetriedMoreTimesThanServerErrors() {
        for (int i = 0; i < LangfuseRestClient.MAX_RATE_LIMIT_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
        }

        assertThatThrownBy(() -> client.upsertItem("d", "d:1", Map.of(), Map.of(), Map.of()))
                .isInstanceOf(EvalReportException.class).hasMessageContaining("429");
        assertThat(server.getRequestCount()).isEqualTo(LangfuseRestClient.MAX_RATE_LIMIT_ATTEMPTS);
    }

    @Test
    void requestsArePacedBelowTheConfiguredPerMinuteLimit() {
        long[] now = {0};
        var paced = new LangfuseRestClient(server.url("/").toString(), "pk-lf-test", "sk-lf-test", mapper,
                millis -> { sleeps.add(millis); now[0] += millis * 1_000_000; }, () -> now[0], 60);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        paced.upsertItem("d", "d:1", Map.of(), Map.of(), Map.of());
        paced.upsertItem("d", "d:2", Map.of(), Map.of(), Map.of());

        assertThat(sleeps).containsExactly(1000L); // 60 次/分钟 → 两次请求间隔 1 秒
    }

    @Test
    void listItemsFollowsPagination() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"data\":[{\"id\":\"d:1\",\"input\":{\"query\":\"一\"},\"expectedOutput\":{\"k\":1},\"metadata\":{\"tier\":50}}],"
                        + "\"meta\":{\"page\":1,\"totalPages\":2}}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"data\":[{\"id\":\"d:2\",\"input\":{\"query\":\"二\"},\"expectedOutput\":null,\"metadata\":null}],"
                        + "\"meta\":{\"page\":2,\"totalPages\":2}}"));

        Map<String, EvalReportPort.StoredItem> items = client.listItems("kwiki-rag-50q");

        assertThat(items).containsOnlyKeys("d:1", "d:2");
        assertThat(items.get("d:1").input().get("query").asText()).isEqualTo("一");
        RecordedRequest first = server.takeRequest();
        assertThat(first.getMethod()).isEqualTo("GET");
        assertThat(first.getPath()).startsWith("/api/public/dataset-items?datasetName=kwiki-rag-50q").contains("page=1");
        assertThat(server.takeRequest().getPath()).contains("page=2");
    }
}

