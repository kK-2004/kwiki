package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.UnweightedGraphProjection;
import com.kwiki.graph.config.ArcadeDbProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原生 Leiden：计算库中按 run 建类型/灌数据/算/删类型的请求序列与 RID→实体映射。 */
class ArcadeDbNativeCommunityDetectionTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.shutdown();
    }

    private static CommunityDetectionInput input(List<String> entities,
                                                 List<UnweightedGraphProjection.Edge> edges) {
        var projection = new UnweightedGraphProjection(7, 2, entities, edges,
                UnweightedGraphProjection.hash(7, 2, entities, edges));
        return new CommunityDetectionInput(7, 2, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                10, 1.0, projection.projectionHash(), "12", 3, projection);
    }

    private ArcadeDbNativeCommunityDetection detection() {
        var properties = new ArcadeDbProperties(URI.create(server.url("/").toString()), "kwiki", "t",
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(3), 8, new ArcadeDbProperties.Tls(false, true), "kwiki_leiden", null);
        var adapter = new ArcadeDbHttpAdapter(properties, new ObjectMapper());
        return new ArcadeDbNativeCommunityDetection(adapter,
                new ArcadeDbScratchProjectionSpace(adapter, properties), new ObjectMapper());
    }

    private void enqueue(String... bodies) {
        for (String body : bodies) {
            server.enqueue(body.startsWith("HTTP ")
                    ? new MockResponse().setResponseCode(Integer.parseInt(body.substring(5)))
                    : new MockResponse().setResponseCode(200).setBody(body));
        }
    }

    private List<RecordedRequest> requests() throws Exception {
        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = server.getRequestCount(); i > 0; i--) requests.add(server.takeRequest());
        return requests;
    }

    @Test
    void 在计算库中按run类型执行Cypher版Leiden并只保留本run顶点() throws Exception {
        server = new MockWebServer();
        // 2 条残留清理 + 4 条建类型 + 实体 + 边 + 算法 + RID 查询 + 2 条删类型
        enqueue("{}", "{}", "{}", "{}", "{}", "{}",
                "{\"result\":[]}", "{\"result\":[]}",
                "{\"result\":[{\"nodeId\":\"#6:0\",\"community\":0},"
                        + "{\"nodeId\":\"#1:0\",\"community\":2},{\"nodeId\":\"#1:1\",\"community\":2},"
                        + "{\"nodeId\":\"#1:2\",\"community\":3}]}",
                "{\"result\":[{\"rid\":\"#1:0\",\"entityId\":\"a\"},{\"rid\":\"#1:1\",\"entityId\":\"b\"},"
                        + "{\"rid\":\"#1:2\",\"entityId\":\"c\"}]}",
                "{}", "{}");
        server.start();

        var result = detection().detect(input(List.of("a", "b", "c"),
                List.of(new UnweightedGraphProjection.Edge("a", "b"))));

        assertThat(result.memberships()).extracting(CommunityMembership::entityId,
                        CommunityMembership::communityId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", "c2"),
                        org.assertj.core.groups.Tuple.tuple("b", "c2"),
                        org.assertj.core.groups.Tuple.tuple("c", "c3"));
        List<RecordedRequest> requests = requests();
        assertThat(requests).hasSize(12);
        assertThat(requests).allSatisfy(request -> assertThat(request.getPath()).endsWith("/kwiki_leiden"));
        assertThat(requests.get(0).getBody().readUtf8()).contains("DROP TYPE LeidenEdge_12 IF EXISTS UNSAFE");
        assertThat(requests.get(2).getBody().readUtf8()).contains("CREATE VERTEX TYPE LeidenEntity_12");
        assertThat(requests.get(6).getBody().readUtf8())
                .contains("\"language\":\"cypher\"", "UNWIND", ":LeidenEntity_12", "\"a\"");
        assertThat(requests.get(7).getBody().readUtf8()).contains("[:LeidenEdge_12]", "\"left\":\"a\"");
        assertThat(requests.get(8).getBody().readUtf8())
                .contains("\"language\":\"cypher\"", "CALL algo.leiden('LeidenEdge_12', 10, 1.0)");
        assertThat(requests.get(9).getPath()).isEqualTo("/api/v1/query/kwiki_leiden");
        assertThat(requests.get(9).getBody().readUtf8()).contains("FROM LeidenEntity_12");
        assertThat(requests.get(10).getBody().readUtf8()).contains("DROP TYPE LeidenEdge_12");
        assertThat(requests.get(11).getBody().readUtf8()).contains("DROP TYPE LeidenEntity_12");
    }

    @Test
    void 中途失败时仍删除本run类型并抛出原错误() throws Exception {
        server = new MockWebServer();
        enqueue("{}", "{}", "{}", "{}", "{}", "{}",
                "{\"result\":[]}", "HTTP 500", "{}", "{}");
        server.start();

        assertThatThrownBy(() -> detection().detect(input(List.of("a", "b"),
                List.of(new UnweightedGraphProjection.Edge("a", "b")))))
                .isInstanceOf(ArcadeDbClientException.class);
        // 边写入失败（第 8 个请求）后仍删除本 run 的类型
        List<RecordedRequest> requests = requests();
        assertThat(requests.get(requests.size() - 1).getBody().readUtf8())
                .contains("DROP TYPE LeidenEntity_12 IF EXISTS UNSAFE");
    }

    @Test
    void 空投影不访问ArcadeDB() throws Exception {
        server = new MockWebServer();
        server.start();

        var result = detection().detect(input(List.of(), List.of()));

        assertThat(result.memberships()).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }
}
