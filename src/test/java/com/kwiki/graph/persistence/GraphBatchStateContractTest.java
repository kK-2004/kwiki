package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 批次状态随子任务取消、重试与执行同步：批次不再停在「排队中」。 */
class GraphBatchStateContractTest {

    @Test
    void cancellingTheLastActiveRunSettlesTheBatchAsCancelled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("SET state = 'CANCELLED'"), eq(6L))).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT batch_id FROM graph_build_run"), eq(Long.class), eq(6L)))
                .thenReturn(5L);
        // 结算：无未结束子任务，1 个失败类（即被取消的那个），0 个成功
        when(jdbc.queryForObject(contains("state NOT IN"), eq(Long.class), eq(5L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("IN ('FAILED','CANCELLED','STALE')"), eq(Long.class), eq(5L)))
                .thenReturn(1L);
        when(jdbc.queryForObject(contains("IN ('READY','PUBLISHED')"), eq(Long.class), eq(5L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("state = 'CANCELLED'"), eq(Long.class), eq(5L))).thenReturn(1L);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.cancelRun(6L)).isTrue();

        verify(jdbc).update("UPDATE graph_build_batch SET state = ? WHERE id = ?", "CANCELLED", 5L);
    }

    @Test
    void cancelNoLongerFakesACleaningStage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("SET state = 'CANCELLED'"), eq(6L))).thenReturn(0);
        new JdbcGraphBuildRepository(jdbc).cancelRun(6L);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(6L));
        assertThat(sql.getValue()).doesNotContain("CLEANING");
        // 没有取消成功（子任务已结束）：不结算批次
        verify(jdbc, never()).queryForObject(contains("SELECT batch_id"), eq(Long.class), anyLong());
    }

    @Test
    void mixedFailureAndCancellationSettlesAsFailed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("state NOT IN"), eq(Long.class), eq(5L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("IN ('FAILED','CANCELLED','STALE')"), eq(Long.class), eq(5L)))
                .thenReturn(2L);
        when(jdbc.queryForObject(contains("IN ('READY','PUBLISHED')"), eq(Long.class), eq(5L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("state = 'CANCELLED'"), eq(Long.class), eq(5L))).thenReturn(1L);

        new JdbcGraphBuildRepository(jdbc).settleBatch(5L);

        verify(jdbc).update("UPDATE graph_build_batch SET state = ? WHERE id = ?", "FAILED", 5L);
    }

    @Test
    void retryingARunReopensItsFinishedBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("SET state = 'QUEUED', stage = 'QUEUED'"), eq(6L))).thenReturn(1);

        assertThat(new JdbcGraphBuildRepository(jdbc).requeueRun(6L)).isTrue();

        verify(jdbc).update(contains("UPDATE graph_build_batch SET state = 'QUEUED'"), eq(6L));
    }

    @Test
    void retryIsRefusedForRunsOfDeletedBatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcGraphBuildRepository(jdbc).requeueRun(6L);

        verify(jdbc).update(contains("batch_id NOT IN (SELECT id FROM graph_build_batch WHERE deleted_at IS NOT NULL)"),
                eq(6L));
        // 未重新排队：不改批次状态
        verify(jdbc, never()).update(contains("UPDATE graph_build_batch"), eq(6L));
    }

    @Test
    void snapshotResourcesAreRegisteredIdempotentlyFromTheSnapshotRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcGraphBuildRepository(jdbc).ensureSnapshotResources(93L);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), eq(93L));
        assertThat(sql.getAllValues()).allMatch(statement -> statement.contains("INSERT IGNORE"));
        assertThat(sql.getAllValues().get(0))
                .contains("'" + GraphArtifactCleaner.KIND_GRAPH_VERSION + "'")
                .contains("CONCAT(kb_id, ':', graph_version)");
        assertThat(sql.getAllValues().get(1))
                .contains("'" + GraphArtifactCleaner.KIND_COMMUNITY_INDEX + "'")
                .contains("community_physical_index IS NOT NULL");
    }

    @Test
    void cleanupCandidatesSkipBatchDeletionsAndFullyCleanedSnapshots() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcGraphBuildRepository(jdbc).findSnapshotsAwaitingCleanup();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(Long.class));
        assertThat(sql.getValue())
                .contains("s.state = 'DELETING'")
                .contains("b.deleted_at IS NULL")
                .contains("g.cleanup_state <> 'DELETED'");
    }

    @Test
    void modelAttemptFailuresAccumulateUnderTheFencingToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcGraphBuildRepository(jdbc).recordModelAttemptFailure(12L, "owner-a", 3L, 1, 1);

        verify(jdbc).update(contains("model_retries = model_retries + ?, model_timeouts = model_timeouts + ?"),
                eq(1), eq(1), eq(12L), eq("owner-a"), eq(3L));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(1), eq(1), eq(12L), eq("owner-a"), eq(3L));
        assertThat(sql.getValue()).contains("lease_owner = ? AND fencing_token = ?");
    }

    @Test
    void retryingARunResetsItsModelCallStats() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcGraphBuildRepository(jdbc).requeueRun(6L);

        verify(jdbc).update(contains("model_retries = 0, model_timeouts = 0"), eq(6L));
    }
}
