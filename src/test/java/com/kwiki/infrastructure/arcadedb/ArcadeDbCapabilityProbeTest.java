package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void enablesBuildOnlyWhenVersionAndAllCapabilitiesAreKnown() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"version":"24.6.2","capabilities":{"leiden":true,
                "leidenInputOutput":true,"schema":true,"databaseCreate":true}}
                """));
        server.start();
        var properties = properties(server.url("/").toString());
        var report = new ArcadeDbCapabilityProbe(
                new ArcadeDbHttpAdapter(properties, new ObjectMapper()), properties, new ObjectMapper()).probe();

        assertThat(report.buildEnabled()).isTrue();
        assertThat(report.serverVersion()).isEqualTo("24.6.2");
    }

    @Test
    void unknownCapabilitiesFailClosedWithoutAlgorithmFallback() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"version\":\"24.6.2\",\"capabilities\":{}}"));
        server.start();
        var properties = properties(server.url("/").toString());
        var report = new ArcadeDbCapabilityProbe(
                new ArcadeDbHttpAdapter(properties, new ObjectMapper()), properties, new ObjectMapper()).probe();

        assertThat(report.buildEnabled()).isFalse();
        assertThat(report.failureCode()).isEqualTo("leiden-capability-missing");
    }

    private static ArcadeDbProperties properties(String endpoint) {
        return new ArcadeDbProperties(URI.create(endpoint), "kwiki", "arcade-contract-token",
                Duration.ofSeconds(3), Duration.ofMillis(1500), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 8, new ArcadeDbProperties.Tls(false, true),
                "kwiki_leiden_", "24.6");
    }
}
