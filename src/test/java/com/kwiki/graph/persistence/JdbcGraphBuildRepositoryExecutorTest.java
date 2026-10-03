package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.build.RunProgress;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 执行器所需的仓储扩展：领取、并发、进度、失败、收尾、清单与快照落库。 */
class JdbcGraphBuildRepositoryExecutorTest {

    private static final Instant NOW = Instant.parse("2026-10-03T02:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

    /** 让 mock JdbcTemplate 返回一行可映射的 graph_build_run 结果。 */
    @SuppressWarnings("unchecked")
    private void stubRunRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong(any(String.class))).thenReturn(11L);
        when(row.getInt(any(String.class))).thenReturn(3);
        when(row.getTimestamp(any(String.class))).thenReturn(null);
        when(row.getString("state")).thenReturn("QUEUED");
        when(row.getString("stage")).thenReturn("EXTRACTING");
        when(row.getString("chunk_index_schema")).thenReturn("kwiki-schema-1");
        when(row.getString("entity_linking_version")).thenReturn("entity-linking-v1");
        when(row.getString("chunk_physical_index")).thenReturn("kwiki-chunks-v3");
        when(row.getString("community_physical_index")).thenReturn("kwiki-communities-v5-kb7");
        when(row.getString("extractor")).thenReturn("kwiki-extractor-1");
        when(row.getString("community_detector")).thenReturn("arcadedb-leiden-1");
        when(row.getString("summarizer")).thenReturn("kwiki-summarizer-1");
        when(jdbc.query(contains("state = 'QUEUED'"), any(RowMapper.class),
                any(Timestamp.class), any(Integer.class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper<GraphBuildRunRecord>) invocation.getArgument(1)).mapRow(row, 0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findClaimableRuns按批次与id排序并携带插件列() throws Exception {
        stubRunRow();

        List<GraphBuildRunRecord> runs = repository.findClaimableRuns(NOW, 5);

        assertThat(runs).hasSize(1);
        GraphBuildRunRecord run = runs.get(0);
        assertThat(run.id()).isEqualTo(11L);
        assertThat(run.state()).isEqualTo(GraphBuildState.QUEUED);
        assertThat(run.extractor()).isEqualTo("kwiki-extractor-1");
        assertThat(run.communityDetector()).isEqualTo("arcadedb-leiden-1");
        assertThat(run.summarizer()).isEqualTo("kwiki-summarizer-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class),
                any(Timestamp.class), any(Integer.class));
        assertThat(sql.getValue()).contains("ORDER BY batch_id, id LIMIT ?")
                .contains("lease_expires_at < ?");
    }

    @Test
    void countLiveRuns只统计租约未过期的活动子任务() {
        when(jdbc.queryForObject(contains("lease_expires_at >= ?"), eq(Long.class),
                any(Timestamp.class))).thenReturn(3L);

        assertThat(repository.countLiveRuns(NOW)).isEqualTo(3);
    }

    @Test
    void 进度更新带租约与隔离令牌保护() {
        when(jdbc.update(contains("extraction_cache_hits = ?"), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(1);

        assertThat(repository.updateProgress(11, "owner-a", 5,
                new RunProgress(20, 8, 3, 0, 0))).isTrue();
        verify(jdbc).update(contains("extraction_cache_hits = ?"), eq(20L), eq(8L), eq(3L),
                eq(0L), eq(0L), eq(11L), eq("owner-a"), eq(5L));
        verify(jdbc).update(contains("WHERE id = ? AND lease_owner = ? AND fencing_token = ?"),
                eq(20L), eq(8L), eq(3L), eq(0L), eq(0L), eq(11L), eq("owner-a"), eq(5L));
    }

    @Test
    void 失败更新写completed_at并带令牌保护() {
        when(jdbc.update(contains("state = 'FAILED'"), any(), any(), any(), any(), any()))
                .thenReturn(1);

        assertThat(repository.failRun(11, "owner-a", 5, "EXTRACTION_INVALID", "抽取校验失败"))
                .isTrue();
        verify(jdbc).update(contains("completed_at = CURRENT_TIMESTAMP(6)"),
                eq("EXTRACTION_INVALID"), eq("抽取校验失败"), eq(11L), eq("owner-a"), eq(5L));
    }

    @Test
    void markStale带令牌保护并写completed_at() {
        when(jdbc.update(contains("state = 'STALE'"), any(), any(), any())).thenReturn(1);

        assertThat(repository.markStale(11, "owner-a", 5)).isTrue();
        verify(jdbc).update(contains("state = 'STALE'"), eq(11L), eq("owner-a"), eq(5L));
    }

    @Test
    void 仍有活动子任务时批次不收尾() {
        when(jdbc.queryForObject(contains("state NOT IN"), eq(Long.class), eq(9L))).thenReturn(1L);

        repository.settleBatch(9L);

        verify(jdbc, never()).update(contains("UPDATE graph_build_batch SET state = ?"),
                any(), any());
    }

    @Test
    void 全部成功时批次收尾为READY() {
        stubSettle(0L, 0L, 2L);

        repository.settleBatch(9L);

        verify(jdbc).update(contains("UPDATE graph_build_batch SET state = ?"), eq("READY"), eq(9L));
    }

    @Test
    void 部分失败时批次收尾为PARTIAL_FAILED() {
        stubSettle(0L, 1L, 1L);

        repository.settleBatch(9L);

        verify(jdbc).update(contains("UPDATE graph_build_batch SET state = ?"),
                eq("PARTIAL_FAILED"), eq(9L));
    }

    @Test
    void 全部失败时批次收尾为FAILED() {
        stubSettle(0L, 2L, 0L);

        repository.settleBatch(9L);

        verify(jdbc).update(contains("UPDATE graph_build_batch SET state = ?"), eq("FAILED"), eq(9L));
    }

    /** (未完成数, 失败数, 成功数)。 */
    private void stubSettle(long unfinished, long failing, long succeeded) {
        when(jdbc.queryForObject(contains("state NOT IN"), eq(Long.class), any(Long.class)))
                .thenReturn(unfinished);
        when(jdbc.queryForObject(contains("AND state IN ('FAILED','CANCELLED','STALE')"),
                eq(Long.class), any(Long.class))).thenReturn(failing);
        when(jdbc.queryForObject(contains("AND state IN ('READY','PUBLISHED')"),
                eq(Long.class), any(Long.class))).thenReturn(succeeded);
    }

    @Test
    void persistManifest写COMPLETE清单与全部清单项() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("graph_source_manifest"),
                eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    invocation.<PreparedStatementCreator>getArgument(0)
                            .createPreparedStatement(connection);
                    invocation.<KeyHolder>getArgument(1).getKeyList().add(Map.of("id", 77L));
                    return 1;
                });
        when(jdbc.update(contains("graph_source_manifest_entry"), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        long manifestId = repository.persistManifest(manifest());

        assertThat(manifestId).isEqualTo(77L);
        verify(statement).setLong(1, 12L);
        verify(statement).setLong(7, 1L);
        verify(statement).setString(3, "manifest-hash");
        // 清单项写入来源身份与 READY 状态
        GraphSourceChunk source = manifest().entries().get(0).sourceChunk();
        verify(jdbc).update(contains("graph_source_manifest_entry"), eq(77L),
                eq(source.sourceChunkId()), eq("PAGE"), eq(42L), eq(3L), eq(1L),
                eq("kb7:page:42:chunk:0"),
                eq("hash-1"), eq("parser-1"), eq("chunker-1"), eq(5L),
                eq("entity-linking-v1"), eq("READY"));
    }

    @Test
    void createSnapshot写入全部NOTNULL列与插件组合() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("graph_snapshot"),
                eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    invocation.<PreparedStatementCreator>getArgument(0)
                            .createPreparedStatement(connection);
                    invocation.<KeyHolder>getArgument(1).getKeyList().add(Map.of("id", 91L));
                    return 1;
                });

        GraphBuildRunRecord run = new GraphBuildRunRecord(12L, 9L, 7L, 3, "kwiki-chunks-v3",
                5L, "kwiki-communities-v5-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.SUMMARIZING, GraphBuildStage.SUMMARIZING, 6L,
                "owner-a", null, 27L, "kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
        long snapshotId = repository.createSnapshot(run, 77L, "manifest-hash",
                new GraphSnapshotIdentity("kwiki-extractor-1", "arcadedb-leiden-1",
                        "kwiki-summarizer-1", "ARCADEDB_NATIVE_UNWEIGHTED", "25.1",
                        com.kwiki.infrastructure.arcadedb.ArcadeDbSchemaDefinition.SCHEMA_VERSION,
                        "summary-model", "community-summary-v1", "text-embedding-model", 1024));

        assertThat(snapshotId).isEqualTo(91L);
        verify(statement).setLong(1, 12L);
        verify(statement).setLong(8, 77L);
        verify(statement).setString(9, "manifest-hash");
        verify(statement).setString(10, "entity-linking-v1");
        verify(statement).setString(11, "kwiki-schema-1");
        verify(statement).setInt(12,
                com.kwiki.infrastructure.arcadedb.ArcadeDbSchemaDefinition.SCHEMA_VERSION);
        verify(statement).setLong(19, 8L);
        verify(statement).setLong(20, 2L);
        verify(statement).setString(21, "kwiki-extractor-1");
        verify(statement).setString(23, "kwiki-summarizer-1");
    }

    @Test
    void 快照计数只在BUILDING状态更新() {
        when(jdbc.update(contains("state = 'BUILDING'"), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1);

        assertThat(repository.updateSnapshotCounts(91L, 10, 20, 3)).isTrue();
        verify(jdbc).update(contains("community_count = ?"), eq(10L), eq(20L), eq(3L), eq(91L));
    }

    private static GraphSourceManifest manifest() {
        GraphSourceChunk source = new GraphSourceChunk(7L, "PAGE", 42L, 3L, 1, 5,
                "parser-1", "chunker-1", "kb7:page:42:chunk:0", "hash-1");
        return new GraphSourceManifest(12L, 7L, 5, "entity-linking-v1", 8L, 2L, 0L,
                true, List.of(new GraphSourceManifestEntry(source, "entity-linking-v1",
                GraphSourceReadiness.READY)), "manifest-hash");
    }
}
