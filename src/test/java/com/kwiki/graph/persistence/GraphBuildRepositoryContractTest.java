package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphBuildRepositoryContractTest {

    @Test
    void allocatesCommunityVersionsFromLockedMonotonicSequence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("MAX(version_number)"), eq(Long.class))).thenReturn(9L);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.allocateCommunityIndexVersion("kwiki-communities-v10-kb{kbId}", 2, 3))
                .isEqualTo(10);
        verify(jdbc).update(contains("INSERT INTO community_index_version"),
                eq(10L), eq("kwiki-communities-v10-kb{kbId}"), eq(2), eq(3L));
    }

    @Test
    void leaseAndCheckpointUpdatesCarryOwnerAndFencingToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("fencing_token = fencing_token + 1"), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbc.update(contains("event_watermark = ?"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.acquireLease(11, "worker-a", Duration.ofSeconds(30), 4)).isTrue();
        assertThat(repository.checkpoint(11, "worker-a", 5, GraphBuildState.PROJECTING,
                GraphBuildStage.PROJECTING, 27)).isTrue();
        verify(jdbc).update(contains("lease_owner = ?"), any(), any(), eq(11L), eq(4L));
        verify(jdbc).update(contains("event_watermark = ?"), eq("PROJECTING"), eq("PROJECTING"),
                eq(27L), eq(11L), eq("worker-a"), eq(5L));
    }

    @Test
    void publicationUsesConditionalSinglePointerUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("INSERT IGNORE INTO graph_publication"), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbc.update(contains("active_snapshot_id IS NULL"), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(1);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.compareAndSetPublication(7, 3, null, 91, 12, 4,
                java.time.Instant.EPOCH)).isTrue();
        verify(jdbc).update(contains("active_snapshot_id IS NULL"), any(), eq(12L), eq(4L),
                any(), eq(7L), eq(3), eq(12L), eq(4L));
    }

    @Test
    void sealingReadyOnlyTouchesBuildingSnapshotsAndPersistsFailedReportsSeparately() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("state = 'READY'"), any(String.class), any(Long.class)))
                .thenReturn(1);
        when(jdbc.update(contains("SET validation_json = ?"), any(String.class), any(Long.class)))
                .thenReturn(1);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.sealSnapshotReady(91, "{\"checks\":[]}")).isTrue();
        verify(jdbc).update(contains("state = 'READY'"), eq("{\"checks\":[]}"), eq(91L));

        assertThat(repository.persistValidationReport(91, "{\"checks\":[\"failed\"]}")).isTrue();
        verify(jdbc).update(contains("SET validation_json = ?"),
                eq("{\"checks\":[\"failed\"]}"), eq(91L));
    }

    @Test
    void readLeaseInsertOnlyTargetsPinnableSnapshots() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(org.springframework.jdbc.support.KeyHolder.class)))
                .thenAnswer(invocation -> {
                    org.springframework.jdbc.support.KeyHolder holder =
                            invocation.getArgument(1);
                    holder.getKeyList().add(java.util.Map.of("id", 555L));
                    return 1;
                });
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.acquireReadLease(91, java.time.Instant.now()
                .plus(java.time.Duration.ofMinutes(10)))).isEqualTo(555L);
    }

    @Test
    void readLeaseInsertReturningNoRowMeansSnapshotRefusesPin() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(org.springframework.jdbc.support.KeyHolder.class))).thenReturn(0);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.acquireReadLease(91, java.time.Instant.now()
                .plus(java.time.Duration.ofMinutes(10)))).isZero();
    }

    @Test
    void activeReadLeasesExpireWithWallClock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("graph_snapshot_read_lease"), eq(Boolean.class), any()))
                .thenReturn(true, false);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.hasActiveReadLeases(91)).isTrue();
        assertThat(repository.hasActiveReadLeases(91)).isFalse();
        verify(jdbc, org.mockito.Mockito.times(2)).queryForObject(
                contains("expires_at > CURRENT_TIMESTAMP"), eq(Boolean.class), eq(91L));
    }

    @Test
    void releaseReadLeaseDeletesExactlyOneRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("DELETE FROM graph_snapshot_read_lease"), eq(555L)))
                .thenReturn(1);
        JdbcGraphBuildRepository repository = new JdbcGraphBuildRepository(jdbc);

        assertThat(repository.releaseReadLease(555L)).isTrue();
        verify(jdbc).update(contains("DELETE FROM graph_snapshot_read_lease"), eq(555L));
    }
}
