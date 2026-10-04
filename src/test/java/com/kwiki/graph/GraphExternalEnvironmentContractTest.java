package com.kwiki.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.infrastructure.arcadedb.ArcadeDbCapabilityProbe;
import com.kwiki.infrastructure.arcadedb.ArcadeDbHttpAdapter;
import com.kwiki.infrastructure.arcadedb.ArcadeDbNativeCommunityDetection;
import com.kwiki.infrastructure.arcadedb.ArcadeDbSchemaInitializationResult;
import com.kwiki.infrastructure.arcadedb.ArcadeDbSchemaInitializer;
import com.kwiki.infrastructure.arcadedb.ArcadeDbScratchProjectionSpace;
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
 * 业务库默认 kwiki_contract（KWIKI_GRAPH_CONTRACT_ARCADEDB_DATABASE），计算库默认 kwiki_leiden
 * （KWIKI_GRAPH_CONTRACT_ARCADEDB_SCRATCH）；两者都需管理员预建并授权给 token。
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
                // 契约测试可能走公网，单次往返 1~3s，查询超时放宽到 10s
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 4,
                new ArcadeDbProperties.Tls(false, true),
                envOrDefault("KWIKI_GRAPH_CONTRACT_ARCADEDB_SCRATCH", "kwiki_leiden"), null);
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
        assertThat(report.schemaSupported()).as("业务库与计算库已由管理员创建且 token 可访问").isTrue();
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
    void nativeLeidenSplitsTwoTrianglesAndDropsRunTypes() {
        ArcadeDbProperties properties = properties();
        ArcadeDbHttpAdapter client = new ArcadeDbHttpAdapter(properties, new ObjectMapper());
        ArcadeDbScratchProjectionSpace scratch = new ArcadeDbScratchProjectionSpace(client, properties);
        // 两个互不相连的三角形加一个孤点。注意：实测 26.9.1 的 algo.leiden 在两三角形间
        // 有桥边、resolution=1.0 时会合并为一个社区，这里只断言引擎确定能分开的情形
        var entities = java.util.List.of("a1", "a2", "a3", "b1", "b2", "b3", "iso");
        var edges = java.util.List.of(
                new UnweightedGraphProjection.Edge("a1", "a2"), new UnweightedGraphProjection.Edge("a1", "a3"),
                new UnweightedGraphProjection.Edge("a2", "a3"), new UnweightedGraphProjection.Edge("b1", "b2"),
                new UnweightedGraphProjection.Edge("b1", "b3"), new UnweightedGraphProjection.Edge("b2", "b3"));
        var projection = new UnweightedGraphProjection(9001, 1, entities, edges,
                UnweightedGraphProjection.hash(9001, 1, entities, edges));

        CommunityDetectionResult result = new ArcadeDbNativeCommunityDetection(client, scratch,
                new ObjectMapper()).detect(new CommunityDetectionInput(
                9001, 1, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                10, 1.0, projection.projectionHash(), "9001", 1, projection));

        var byEntity = new java.util.HashMap<String, String>();
        result.memberships().forEach(m -> byEntity.put(m.entityId(), m.communityId()));
        assertThat(byEntity.keySet()).containsExactlyInAnyOrderElementsOf(entities);
        assertThat(byEntity.get("a1")).isEqualTo(byEntity.get("a2")).isEqualTo(byEntity.get("a3"));
        assertThat(byEntity.get("b1")).isEqualTo(byEntity.get("b2")).isEqualTo(byEntity.get("b3"));
        assertThat(byEntity.get("a1")).isNotEqualTo(byEntity.get("b1"));
        var leftover = client.query(properties.scratchDatabase(),
                "SELECT name FROM schema:types WHERE name LIKE 'Leiden%_9001'", java.util.Map.of());
        assertThat(leftover.body()).as("本 run 的临时类型已删除").doesNotContain("Leiden");
    }

    @Test
    void writesGraphAndAssignsCommunitiesInBusinessDatabase() {
        ArcadeDbProperties properties = properties();
        ArcadeDbHttpAdapter client = new ArcadeDbHttpAdapter(properties, new ObjectMapper());
        var store = new com.kwiki.infrastructure.arcadedb.ArcadeDbKnowledgeGraphStore(
                client, properties, new ObjectMapper(), new ArcadeDbSchemaInitializer(client, properties));
        var entities = java.util.List.of(
                new GraphEntity("e_contract_1", "契约实体一", GraphEntityType.CONCEPT, java.util.List.of("别名")),
                new GraphEntity("e_contract_2", "契约实体二", GraphEntityType.CONCEPT, java.util.List.of()));
        var relations = java.util.List.of(new GraphRelation("r_contract_1", "e_contract_1",
                GraphPredicate.RELATED_TO, "e_contract_2", 0.9,
                java.util.List.of(new GraphSourceRef("chunk_contract_1", 0, 10))));
        var request = new GraphWriteRequest(9001, 1, java.util.List.of(), entities, relations,
                "9001", 1);
        // UPSERT 可重入：同一请求写两次不报错、不重复
        store.write(request);
        store.write(request);
        store.assignCommunities(9001, 1, java.util.List.of(
                new CommunityMembership(9001, 1, "e_contract_1", "c0"),
                new CommunityMembership(9001, 1, "e_contract_2", "c0")));

        var rows = client.query(properties.database(),
                "SELECT entityId, communityId FROM Entity WHERE kbId = :kbId AND graphVersion = :graphVersion",
                java.util.Map.of("kbId", 9001, "graphVersion", 1));
        assertThat(rows.body()).contains("e_contract_1", "e_contract_2", "\"c0\"");
        assertThat(rows.body().split("\"e_contract_1\"", -1)).as("实体未重复写入").hasSize(2);

        var explained = store.explainEntityLookup(9001, 1, "e_contract_probe");
        assertThat(explained).isNotNull();
    }

}
