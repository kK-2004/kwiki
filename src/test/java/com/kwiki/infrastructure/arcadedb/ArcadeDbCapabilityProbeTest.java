package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.ArcadeDbCapabilityReport;
import com.kwiki.graph.config.ArcadeDbProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ArcadeDbCapabilityProbeTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.shutdown();
    }

    /** 26.9.1 的真实返回（GET /api/v1/server?mode=basic）。 */
    private static final String REAL_26_9_1 = """
            {"user":"root","version":"26.9.1 (build 9cea8e848fa57d275b8d614328245508cd76614a/1788462493960/main)",
            "serverName":"ArcadeDB_0","languages":["java","sql","sqlscript","opencypher","cypher"]}
            """;

    private ArcadeDbCapabilityReport probe(String body, String requiredVersion) throws Exception {
        return probe(body, requiredVersion, true, true);
    }

    private ArcadeDbCapabilityReport probe(String body, String requiredVersion,
                                           boolean businessExists, boolean scratchExists) throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"result\":" + businessExists + "}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"result\":" + scratchExists + "}"));
        server.start();
        var properties = properties(server.url("/").toString(), requiredVersion);
        return new ArcadeDbCapabilityProbe(
                new ArcadeDbHttpAdapter(properties, new ObjectMapper()), properties, new ObjectMapper()).probe();
    }

    @Test
    void 真实服务返回的版本号满足内置Leiden时可构建() throws Exception {
        var report = probe(REAL_26_9_1, "");

        assertThat(report.leidenSupported()).isTrue();
        assertThat(report.buildEnabled()).isTrue();
        assertThat(report.serverVersion()).isEqualTo("26.9.1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/server?mode=basic");
    }

    @Test
    void 业务库或计算库不存在时不可构建() throws Exception {
        var report = probe(REAL_26_9_1, "", true, false);

        assertThat(report.buildEnabled()).isFalse();
        assertThat(report.failureCode()).isEqualTo("database-missing");
        server.takeRequest();
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/exists/kwiki");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/exists/kwiki_leiden");
    }

    @Test
    void 低于26_3_1的版本没有内置Leiden() throws Exception {
        var report = probe("{\"version\":\"25.12.1 (build x)\"}", "");

        assertThat(report.buildEnabled()).isFalse();
        assertThat(report.failureCode()).isEqualTo("leiden-capability-missing");
    }

    @Test
    void 版本缺失时按不支持处理() throws Exception {
        var report = probe("{\"serverName\":\"ArcadeDB_0\"}", "");

        assertThat(report.buildEnabled()).isFalse();
        assertThat(report.failureCode()).isEqualTo("leiden-capability-missing");
    }

    @Test
    void 锁定版本号按前缀匹配实际版本() throws Exception {
        assertThat(probe(REAL_26_9_1, "26.9").buildEnabled()).isTrue();
    }

    @Test
    void 已锁定版本号但实际版本不符时仍拒绝构建() throws Exception {
        var report = probe(REAL_26_9_1, "26.10");

        assertThat(report.buildEnabled()).isFalse();
        assertThat(report.failureCode()).isEqualTo("unsupported-server-version");
    }

    @Test
    void 版本比较按数值逐段进行() {
        int[] min = ArcadeDbCapabilityProbe.MIN_LEIDEN_VERSION;
        assertThat(ArcadeDbCapabilityProbe.atLeast("26.3.1", min)).isTrue();
        assertThat(ArcadeDbCapabilityProbe.atLeast("26.10.0", min)).isTrue();
        assertThat(ArcadeDbCapabilityProbe.atLeast("27.1", min)).isTrue();
        assertThat(ArcadeDbCapabilityProbe.atLeast("26.3.0", min)).isFalse();
        assertThat(ArcadeDbCapabilityProbe.atLeast("26.2.9", min)).isFalse();
        assertThat(ArcadeDbCapabilityProbe.atLeast("snapshot", min)).isFalse();
    }

    private static ArcadeDbProperties properties(String endpoint) {
        return properties(endpoint, "24.6");
    }

    private static ArcadeDbProperties properties(String endpoint, String requiredServerVersion) {
        return new ArcadeDbProperties(URI.create(endpoint), "kwiki", "arcade-contract-token",
                Duration.ofSeconds(3), Duration.ofMillis(1500), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 8, new ArcadeDbProperties.Tls(false, true),
                "kwiki_leiden", requiredServerVersion);
    }
}
