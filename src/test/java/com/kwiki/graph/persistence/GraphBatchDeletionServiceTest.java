package com.kwiki.graph.persistence;

import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 批次删除：前置校验、墓碑标记、外部数据清理与失败重试。 */
class GraphBatchDeletionServiceTest {

    private static final long BATCH = 5L;

    private record Deps(JdbcOperations jdbc, GraphVersionDataCleaner graph,
                        ElasticsearchIndexManager indexes, GraphBatchDeletionService service) {
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    /** 批次未删除；两个子任务；快照 31、32 未发布、无租约。 */
    private static Deps deps(String runState, Object deletedAt, String cleanupState) {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForList(startsWith("SELECT deleted_at, cleanup_state"), eq(BATCH)))
                .thenReturn(List.of(row("deleted_at", deletedAt, "cleanup_state", cleanupState)));
        when(jdbc.queryForList(startsWith("SELECT id, kb_id, graph_version"), eq(BATCH)))
                .thenReturn(List.of(
                        row("id", 6L, "kb_id", 9L, "graph_version", 3L,
                                "community_physical_index", "kwiki-communities-v3-kb9", "state", runState),
                        row("id", 7L, "kb_id", 10L, "graph_version", 2L,
                                "community_physical_index", "kwiki-communities-v3-kb10", "state", "FAILED")));
        when(jdbc.queryForList(startsWith("SELECT s.id FROM graph_snapshot"), eq(Long.class), eq(BATCH)))
                .thenReturn(List.of(31L, 32L));
        when(jdbc.queryForList(contains("FROM graph_publication"), eq(Long.class))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("graph_snapshot_read_lease"), eq(Boolean.class))).thenReturn(false);
        GraphVersionDataCleaner graph = mock(GraphVersionDataCleaner.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        return new Deps(jdbc, graph, indexes, new GraphBatchDeletionService(jdbc, null, new GraphArtifactCleaner(graph, indexes)));
    }

    @Test
    void finishedBatchIsTombstonedAndItsExternalDataCleaned() throws Exception {
        Deps deps = deps("CANCELLED", null, null);

        var result = deps.service().delete(BATCH, "kk");

        assertThat(result.cleanupState()).isEqualTo("DONE");
        assertThat(result.cleanedRuns()).isEqualTo(2);
        verify(deps.jdbc()).update(contains("UPDATE graph_snapshot SET state = 'DELETING'"));
        verify(deps.jdbc()).update(contains("SET deleted_at = CURRENT_TIMESTAMP(6)"), eq("kk"), eq(BATCH));
        verify(deps.jdbc()).update(contains("UPDATE graph_rebuild_request SET last_submitted_batch_id = NULL"),
                eq(BATCH));
        verify(deps.graph()).deleteGraphVersion(9L, 3L);
        verify(deps.graph()).deleteGraphVersion(10L, 2L);
        verify(deps.indexes()).forceDeleteManagedIndex("kwiki-communities-v3-kb9");
        verify(deps.indexes()).forceDeleteManagedIndex("kwiki-communities-v3-kb10");
        verify(deps.jdbc()).update(contains("SET cleanup_state = ?"), eq("DONE"), eq(null), eq(BATCH));
        verify(deps.jdbc()).update(contains("community_index_version SET state = 'RETIRED'"), eq(BATCH));
        // 墓碑：批次与子任务行不物理删除，避免 graphVersion / COMMUNITY 版本被复用
        verify(deps.jdbc(), never()).update(startsWith("DELETE FROM graph_build_run"), anyLong());
        verify(deps.jdbc(), never()).update(startsWith("DELETE FROM graph_build_batch"), anyLong());
    }

    @Test
    void activeRunBlocksDeletion() throws Exception {
        Deps deps = deps("EXTRACTING", null, null);

        assertThatThrownBy(() -> deps.service().delete(BATCH, "kk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请先取消");
        verify(deps.jdbc(), never()).update(contains("SET deleted_at"), anyString(), anyLong());
        verify(deps.graph(), never()).deleteGraphVersion(anyLong(), anyLong());
    }

    @Test
    void publishedSnapshotBlocksDeletion() throws Exception {
        Deps deps = deps("READY", null, null);
        when(deps.jdbc().queryForList(contains("FROM graph_publication"), eq(Long.class))).thenReturn(List.of(31L));

        assertThatThrownBy(() -> deps.service().delete(BATCH, "kk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照 #31");
        verify(deps.jdbc(), never()).update(contains("SET deleted_at"), anyString(), anyLong());
    }

    @Test
    void liveReadLeaseBlocksDeletion() {
        Deps deps = deps("READY", null, null);
        when(deps.jdbc().queryForObject(contains("graph_snapshot_read_lease"), eq(Boolean.class))).thenReturn(true);

        assertThatThrownBy(() -> deps.service().delete(BATCH, "kk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("进行中的读取");
    }

    @Test
    void cleanupFailureIsRecordedAndCanBeRetried() throws Exception {
        Deps deps = deps("FAILED", null, null);
        doThrow(new IllegalStateException("arcadedb down")).when(deps.graph()).deleteGraphVersion(9L, 3L);

        var first = deps.service().delete(BATCH, "kk");

        assertThat(first.cleanupState()).isEqualTo("FAILED");
        assertThat(first.failures()).singleElement().asString().contains("子任务 #6", "arcadedb down");
        verify(deps.jdbc()).update(contains("SET cleanup_state = ?"), eq("FAILED"),
                org.mockito.ArgumentMatchers.<Object>argThat(summary -> String.valueOf(summary).contains("arcadedb down")),
                eq(BATCH));

        // 重试：批次已是墓碑，不再重新校验子任务，只重做外部清理
        Deps retry = deps("FAILED", java.time.LocalDateTime.now(), "FAILED");
        var second = retry.service().delete(BATCH, "kk");

        assertThat(second.alreadyDeleted()).isTrue();
        assertThat(second.cleanupState()).isEqualTo("DONE");
        verify(retry.graph()).deleteGraphVersion(9L, 3L);
        verify(retry.jdbc(), never()).update(contains("SET deleted_at"), anyString(), anyLong());
    }

    @Test
    void fullyCleanedBatchIsAnIdempotentNoOp() {
        Deps deps = deps("FAILED", java.time.LocalDateTime.now(), "DONE");

        var result = deps.service().delete(BATCH, "kk");

        assertThat(result.alreadyDeleted()).isTrue();
        assertThat(result.cleanupState()).isEqualTo("DONE");
        verify(deps.graph(), never()).deleteGraphVersion(anyLong(), anyLong());
    }

    @Test
    void missingBatchIsRejected() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForList(startsWith("SELECT deleted_at, cleanup_state"), eq(BATCH))).thenReturn(List.of());
        GraphBatchDeletionService service = new GraphBatchDeletionService(jdbc, null, new GraphArtifactCleaner((GraphVersionDataCleaner) null, null));

        assertThatThrownBy(() -> service.delete(BATCH, "kk"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("批次不存在");
    }
}
