package com.kwiki.graph;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.persistence.GraphBuildBatchCommand;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunCommand;
import com.kwiki.graph.persistence.GraphPublicationRecord;
import com.kwiki.graph.persistence.GraphSnapshotEntry;
import com.kwiki.graph.persistence.GraphSnapshotValidationReport;
import com.kwiki.graph.persistence.GraphSourceEpochService;
import com.kwiki.graph.persistence.JdbcGraphBuildRepository;
import com.kwiki.infrastructure.elasticsearch.CommunityIndexNaming;
import com.kwiki.infrastructure.elasticsearch.CommunityMappingBuilder;
import com.kwiki.infrastructure.elasticsearch.CommunitySearchAdapter;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 14.4/14.5 端到端与跨存储演练的可执行骨架：分别由
 * KWIKI_GRAPH_CONTRACT_ES / KWIKI_GRAPH_CONTRACT_MYSQL 显式启用，
 * 未配置时明确报告 skipped，不伪报已演练。
 * <p>MySQL 演练只使用专用契约库（必须为空库，演练自动执行全部 Flyway
 * 迁移后运行），绝不连接共享业务数据库。示例：
 * <pre>
 * KWIKI_GRAPH_CONTRACT_MYSQL=jdbc:mysql://localhost:3306/kwiki_contract \
 * KWIKI_GRAPH_CONTRACT_MYSQL_USER=... KWIKI_GRAPH_CONTRACT_MYSQL_PASS=... \
 * KWIKI_GRAPH_CONTRACT_ES=http://localhost:9200 \
 * ./mvnw test -Dtest=GraphEndToEndDrillContractTest
 * </pre>
 * 页面发布→抽取→问答的完整演练步骤见 docs/graph-arcadedb-operations.md 第 7 节。
 */
class GraphEndToEndDrillContractTest {

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    /** 14.4 ES 骨干：受控 COMMUNITY 物理索引的建库、写入、BM25 读回与清理。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "KWIKI_GRAPH_CONTRACT_ES", matches = ".+",
            disabledReason = "未配置 KWIKI_GRAPH_CONTRACT_ES：ES 演练明确跳过")
    void communityPhysicalIndexRoundTripDrill() throws Exception {
        try (RestClient rest = RestClient.builder(
                HttpHost.create(env("KWIKI_GRAPH_CONTRACT_ES"))).build()) {
            ElasticsearchClient client = new ElasticsearchClient(
                    new RestClientTransport(rest, new JacksonJsonpMapper()));
            long communityVersion = 900_000 + System.currentTimeMillis() % 90_000;
            long kbId = 9_001;
            String index = CommunityIndexNaming.physicalName(communityVersion, kbId);
            try {
                String mapping = new ObjectMapper().writeValueAsString(
                        new CommunityMappingBuilder().build(8));
                client.indices().create(create -> create.index(index)
                        .withJson(new StringReader("{\"mappings\":" + mapping + "}")));
                java.util.Map<String, Object> document = new java.util.LinkedHashMap<>();
                document.put("communityKey", "kb_" + kbId + ":42:c_1");
                document.put("kbId", kbId);
                document.put("graphVersion", 42);
                document.put("communityIndexVersion", communityVersion);
                document.put("communityId", "c_1");
                document.put("sourceManifestId", "drill");
                document.put("sourceContentEpoch", 1);
                document.put("sourceSecurityEpoch", 1);
                document.put("sourceChunkIndexVersion", 3);
                document.put("title", "Redisson 分布式锁");
                document.put("summary", "关于看门狗与租约的社区概述");
                document.put("keywords", List.of("Redisson", "锁"));
                document.put("representativeEntityIds", List.of("e_rlock"));
                document.put("sourceRefs", List.of());
                document.put("entityCount", 1);
                client.index(indexRequest -> indexRequest.index(index)
                        .id("kb_" + kbId + ":42:c_1")
                        .document(document));
                client.indices().refresh(refresh -> refresh.index(index));

                CommunitySearchAdapter adapter = new CommunitySearchAdapter(
                        new StaticProvider<>(client));
                CommunitySearchResult result = adapter.search(new CommunitySearchRequest(
                        kbId, 42, communityVersion, index, "分布式锁", null, 3));

                assertThat(result.hits()).hasSize(1);
                assertThat(result.hits().get(0).communityId()).isEqualTo("c_1");
                assertThat(result.hits().get(0).graphVersion()).isEqualTo(42);
            } finally {
                client.indices().delete(delete -> delete.index(index));
            }
        }
    }

    /**
     * 14.5 MySQL 骨干：专用契约库上执行封存→CAS 发布→pin 读取租约→回滚护栏→
     * 退役保护的生命周期；跨存储部分成功/清理由 docs 演练清单覆盖。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "KWIKI_GRAPH_CONTRACT_MYSQL", matches = ".+",
            disabledReason = "未配置 KWIKI_GRAPH_CONTRACT_MYSQL：MySQL 演练明确跳过，"
                    + "且要求专用空库，绝不连接共享业务数据库")
    void publicationLifecycleDrillOnDedicatedMysql() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("KWIKI_GRAPH_CONTRACT_MYSQL"),
                env("KWIKI_GRAPH_CONTRACT_MYSQL_USER"),
                env("KWIKI_GRAPH_CONTRACT_MYSQL_PASS"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long kbId = 90_000 + System.currentTimeMillis() % 9_000;
        GraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);
        GraphSourceEpochService epochs = new GraphSourceEpochService(jdbc);
        long communityVersion = 0;
        long batchId = 0;
        Long snapshotId = null;

        try {
            communityVersion = repository.allocateCommunityIndexVersion(
                    "kwiki-communities-v%d-kb%%d", 3, 1);
            String communityIndex = CommunityIndexNaming.physicalName(communityVersion, kbId);
            long contentEpoch = epochs.advanceContentEpoch(kbId);
            long securityEpoch = epochs.current(kbId)[1];

            batchId = repository.createBatch(new GraphBuildBatchCommand(
                    "drill-" + kbId, "KNOWLEDGE_BASE", "[" + kbId + "]", 3,
                    "kwiki-chunks-v3", communityVersion, false, "drill", null));
            long runId = repository.createRun(new GraphBuildRunCommand(
                    batchId, kbId, 3, "kwiki-chunks-v3", communityVersion,
                    communityIndex, 42, 3, "entity-linking-v1", contentEpoch, securityEpoch));
            jdbc.update("INSERT INTO graph_source_manifest (run_id, kb_id, manifest_hash, "
                    + "content_epoch, security_epoch, event_watermark, entry_count, state) "
                    + "VALUES (?, ?, 'drill', ?, ?, 0, 0, 'COMPLETE')",
                    runId, kbId, contentEpoch, securityEpoch);
            Long manifestId = jdbc.queryForObject(
                    "SELECT id FROM graph_source_manifest WHERE run_id = ?",
                    Long.class, runId);
            jdbc.update("INSERT INTO graph_snapshot (run_id, kb_id, graph_version, "
                    + "chunk_index_version, chunk_physical_index, community_index_version, "
                    + "community_physical_index, source_manifest_id, source_manifest_hash, "
                    + "entity_linking_version, graph_schema_version, mapping_schema_version, "
                    + "algorithm_mode, engine_version, content_epoch, security_epoch) "
                    + "VALUES (?, ?, 42, 3, 'kwiki-chunks-v3', ?, ?, ?, 'drill', "
                    + "'entity-linking-v1', 1, 3, 'ARCADEDB_NATIVE_UNWEIGHTED', 'drill', ?, ?)",
                    runId, kbId, communityVersion, communityIndex, manifestId,
                    contentEpoch, securityEpoch);
            snapshotId = jdbc.queryForObject(
                    "SELECT id FROM graph_snapshot WHERE kb_id = ? AND graph_version = 42",
                    Long.class, kbId);

            String report = new GraphSnapshotValidationReport(kbId, 42, 0, 0, 0, 0, 0, 0, 0,
                    GraphSnapshotValidationReport.REQUIRED_CHECKS.stream()
                            .map(name -> GraphSnapshotValidationReport.Check.pass(name, "ok"))
                            .toList()).toJson();
            assertThat(repository.sealSnapshotReady(snapshotId, report)).isTrue();

            assertThat(repository.compareAndSetPublication(kbId, 3, null, snapshotId,
                    contentEpoch, securityEpoch, Instant.now())).isTrue();

            Optional<GraphSnapshotEntry> pinned = repository.findActivePublicationSnapshot(kbId, 3);
            assertThat(pinned).isPresent();
            assertThat(pinned.get().pinnable()).isTrue();

            long leaseId = repository.acquireReadLease(snapshotId,
                    Instant.now().plus(Duration.ofMinutes(1)));
            assertThat(leaseId).isPositive();
            assertThat(repository.hasActiveReadLeases(snapshotId)).isTrue();
            assertThat(repository.releaseReadLease(leaseId)).isTrue();

            Optional<GraphPublicationRecord> publication = repository.findPublication(kbId, 3);
            assertThat(publication).isPresent();
            assertThat(publication.get().activeSnapshotId()).isEqualTo(snapshotId);

            // 重复幂等发布失败（指针已非空）；最少保留拒绝退役当前快照。
            assertThat(repository.compareAndSetPublication(kbId, 3, null, snapshotId,
                    contentEpoch, securityEpoch, Instant.now())).isFalse();
            assertThat(repository.findRecentSnapshotIds(kbId, 3, 2)).contains(snapshotId);

            // 已封存快照不能再写失败报告（状态门禁），诊断只留给候选。
            String failedReport = new GraphSnapshotValidationReport(kbId, 42, 0, 0, 0, 0, 0,
                    0, 0, List.of(GraphSnapshotValidationReport.Check.fail(
                            GraphSnapshotValidationReport.SOURCE_COVERAGE, "drill"))).toJson();
            assertThat(repository.persistValidationReport(snapshotId, failedReport)).isFalse();
        } finally {
            // 只清理本演练创建的行（专用契约库）。
            if (snapshotId != null) {
                jdbc.update("DELETE FROM graph_snapshot_read_lease WHERE snapshot_id = ?",
                        snapshotId);
            }
            jdbc.update("DELETE FROM graph_publication WHERE kb_id = ?", kbId);
            jdbc.update("DELETE FROM graph_snapshot WHERE kb_id = ?", kbId);
            jdbc.update("DELETE FROM graph_source_manifest WHERE kb_id = ?", kbId);
            jdbc.update("DELETE FROM graph_build_run WHERE kb_id = ?", kbId);
            jdbc.update("DELETE FROM graph_build_batch WHERE idempotency_key = ?",
                    "drill-" + kbId);
            if (communityVersion > 0) {
                jdbc.update("DELETE FROM community_index_version WHERE version_number = ?",
                        communityVersion);
            }
            jdbc.update("DELETE FROM graph_source_epoch WHERE kb_id = ?", kbId);
        }
    }

    private static final class StaticProvider<T>
            implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T value;

        StaticProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }
}
