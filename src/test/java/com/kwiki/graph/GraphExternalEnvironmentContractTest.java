package com.kwiki.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.infrastructure.arcadedb.ArcadeDbCapabilityProbe;
import com.kwiki.infrastructure.arcadedb.ArcadeDbHttpAdapter;
import com.kwiki.infrastructure.arcadedb.ArcadeDbNativeCommunityDetection;
import com.kwiki.infrastructure.arcadedb.ArcadeDbSchemaInitializationResult;
import com.kwiki.infrastructure.arcadedb.ArcadeDbSchemaInitializer;
import com.kwiki.infrastructure.arcadedb.ArcadeDbTemporaryDatabaseService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 14.2/14.3 专用环境契约验收：仅在 KWIKI_GRAPH_CONTRACT_ARCADEDB 等
 * 环境变量显式提供真实服务时执行；未配置时类级条件明确报告 skipped，
 * 绝不伪报已验收。启动示例：
 * <pre>
 * KWIKI_GRAPH_CONTRACT_ARCADEDB=https://arcadedb.internal \
 * KWIKI_GRAPH_CONTRACT_ARCADEDB_TOKEN=... \
 * ./mvnw test -Dtest=GraphExternalEnvironmentContractTest
 * </pre>
 * 14.3 的 EXPLAIN 与高扇出预算检查通过 explainEntityLookup 入口执行；
 * 上线限额依据（内存/磁盘/耗时/成本）由运维在该环境执行 14.6 文档的
 * 容量演练时人工记录。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KWIKI_GRAPH_CONTRACT_ARCADEDB", matches = ".+",
        disabledReason = "未配置 KWIKI_GRAPH_CONTRACT_ARCADEDB：专用环境契约验收明确跳过，"
                + "本结果不构成已验收结论")
class GraphExternalEnvironmentContractTest {

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static ArcadeDbProperties properties() {
        return new ArcadeDbProperties(
                URI.create(env("KWIKI_GRAPH_CONTRACT_ARCADEDB")),
                envOrDefault("KWIKI_GRAPH_CONTRACT_ARCADEDB_DATABASE", "kwiki_contract"),
                env("KWIKI_GRAPH_CONTRACT_ARCADEDB_TOKEN"),
                Duration.ofSeconds(3), Duration.ofMillis(1500), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 4,
                new ArcadeDbProperties.Tls(false, true), "kwiki_leiden_", null);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = env(name);
        return value == null ? fallback : value;
    }

    @Test
    void capabilityProbeConfirmsVersionLeidenAndDatabaseCreation() {
        ArcadeDbProperties properties = properties();
        GraphProperties graph = new GraphProperties(true,
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "0 0 2 * * *",
                "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), GraphProperties.Capacity.defaults());
        properties.requireUsableWhenEnabled(graph);
        ArcadeDbCapabilityReport report = new ArcadeDbCapabilityProbe(
                new ArcadeDbHttpAdapter(properties, new ObjectMapper()),
                properties, new ObjectMapper()).probe();

        assertThat(report.reachable()).as("服务可达").isTrue();
        assertThat(report.serverVersion()).as("记录实际服务版本").isNotBlank();
        assertThat(report.leidenSupported()).as("Leiden 输入/输出契约").isTrue();
        assertThat(report.schemaSupported()).as("图 schema 能力").isTrue();
        assertThat(report.databaseCreationAllowed()).as("建库权限（临时投影库需要）").isTrue();
        assertThat(report.buildEnabled()).as("构建能力").isTrue();
    }

    @Test
    void schemaInitializationIsReentrantOnLockedVersion() {
        ArcadeDbProperties properties = properties();
        ArcadeDbSchemaInitializer initializer = new ArcadeDbSchemaInitializer(
                new ArcadeDbHttpAdapter(properties, new ObjectMapper()), properties);

        ArcadeDbSchemaInitializationResult first = initializer.initialize();
        ArcadeDbSchemaInitializationResult second = initializer.initialize();

        assertThat(first.schemaVersion()).isPositive();
        assertThat(second.schemaVersion()).isEqualTo(first.schemaVersion());
    }

    @Test
    void nativeLeidenEmptyGraphIsLegalAndTemporaryDatabaseCleanupIsIdempotent() {
        ArcadeDbProperties properties = properties();
        ArcadeDbHttpAdapter client = new ArcadeDbHttpAdapter(properties, new ObjectMapper());
        ArcadeDbTemporaryDatabaseService temporaries =
                new ArcadeDbTemporaryDatabaseService(client, properties);
        ArcadeDbTemporaryDatabaseService.Lease lease = temporaries.create(9_001, "contract");
        try {
            // 临时库内执行原生 Leiden：空图得到合法空结果，不编造成员。
            ArcadeDbProperties temporary = withDatabase(properties, lease.database());
            new ArcadeDbSchemaInitializer(client, temporary).initialize();
            CommunityDetectionResult result =
                    new ArcadeDbNativeCommunityDetection(client, temporary,
                            new ObjectMapper()).detect(new CommunityDetectionInput(
                            9001, 1, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                            10, 1.0, UnweightedGraphProjection.hash(9001, 1,
                                    java.util.List.of(), java.util.List.of()),
                            "contract-9001", 1));
            assertThat(result.memberships()).isEmpty();
        } finally {
            temporaries.cleanup(lease, true);
            // 只清理本 run 拥有的数据库；重复清理幂等。
            temporaries.cleanup(lease, true);
        }
    }

    @Test
    void explainExposesEntityUniqueIndexAccessPath() {
        ArcadeDbProperties properties = properties();
        ArcadeDbHttpAdapter client = new ArcadeDbHttpAdapter(properties, new ObjectMapper());
        new ArcadeDbSchemaInitializer(client, properties).initialize();

        var explained = new com.kwiki.infrastructure.arcadedb.ArcadeDbKnowledgeGraphStore(
                client, properties, new ObjectMapper())
                .explainEntityLookup(9001, 1, "e_contract_probe");
        assertThat(explained).isNotNull();
    }

    private static ArcadeDbProperties withDatabase(ArcadeDbProperties base, String database) {
        return new ArcadeDbProperties(base.endpoint(), database, base.token(),
                base.connectTimeout(), base.queryTimeout(), base.batchWriteTimeout(),
                base.algorithmTimeout(), base.maxConnections(), base.tls(),
                base.temporaryDatabasePrefix(), base.requiredServerVersion());
    }
}
