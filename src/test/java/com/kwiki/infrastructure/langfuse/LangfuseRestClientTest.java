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
}
