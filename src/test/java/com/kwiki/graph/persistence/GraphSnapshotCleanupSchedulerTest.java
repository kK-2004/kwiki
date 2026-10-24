package com.kwiki.graph.persistence;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退役快照自动清理：持锁扫描、单个失败不影响其他快照。 */
class GraphSnapshotCleanupSchedulerTest {

    private static KwikiDistributedLocks lockRunningBody() {
        KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
        when(locks.isAvailable()).thenReturn(true);
        when(locks.tryRunReturning(eq("graph:snapshot-cleanup"), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<Integer> body = invocation.getArgument(2);
                    return body.get();
                });
        return locks;
    }

    @Test
    void cleansEveryCandidateAndContinuesAfterAFailure() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshotRetirementService retirement = mock(GraphSnapshotRetirementService.class);
        GraphArtifactCleaner artifacts = new GraphArtifactCleaner((GraphVersionDataCleaner) null, null);
        when(repository.findSnapshotsAwaitingCleanup()).thenReturn(List.of(91L, 92L, 93L));
        when(retirement.cleanupResources(91L, artifacts))
                .thenReturn(new GraphSnapshotRetirementService.CleanupResult(2, 0, true));
        when(retirement.cleanupResources(92L, artifacts)).thenThrow(new IllegalStateException("boom"));
        // 宽限期未到：不清理、不算完成
        when(retirement.cleanupResources(93L, artifacts))
                .thenReturn(new GraphSnapshotRetirementService.CleanupResult(0, 0, false));

        int completed = new GraphSnapshotCleanupScheduler(repository, retirement, artifacts, lockRunningBody())
                .runOnce();

        assertThat(completed).isEqualTo(1);
        verify(retirement).cleanupResources(93L, artifacts);
    }

    @Test
    void unavailableLockSkipsTheRound() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshotRetirementService retirement = mock(GraphSnapshotRetirementService.class);
        KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
        when(locks.isAvailable()).thenReturn(false);

        assertThat(new GraphSnapshotCleanupScheduler(repository, retirement,
                new GraphArtifactCleaner((GraphVersionDataCleaner) null, null), locks).runOnce()).isZero();
        verify(repository, never()).findSnapshotsAwaitingCleanup();
        verify(retirement, never()).cleanupResources(anyLong(), any());
    }
}
