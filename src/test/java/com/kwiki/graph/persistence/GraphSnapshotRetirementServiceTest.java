package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import com.kwiki.graph.config.GraphProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphSnapshotRetirementServiceTest {

    private static final GraphSnapshot SNAPSHOT = new GraphSnapshot(93, 7, 43, 12, 3,
            "kwiki-chunks-v12", "kwiki-communities-v3-kb7", "entity-linking-v1",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 5, 2);

    @Test
    void rollbackSwitchesPointerBackToCompatibleHistoricalSnapshotWithCurrentEpochs() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.RETIRED)));
        when(epochs.currentForUpdate(7)).thenReturn(new long[]{6, 3});
        when(repository.compareAndSetPublication(eq(7L), eq(12), eq(91L), eq(93L),
                eq(6L), eq(3L), any(Instant.class))).thenReturn(true);
        GraphSnapshotRetirementService service = service(repository, epochs);

        assertThat(service.rollback(7, 12, 93, 91L)).isTrue();
    }

    @Test
    void rollbackRejectsBuildingOrChunkIncompatibleSnapshot() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.BUILDING)));
        GraphSnapshotRetirementService service = service(repository, epochs);

        assertThatThrownBy(() -> service.rollback(7, 12, 93, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不完整");
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.RETIRED)));
        assertThatThrownBy(() -> service.rollback(7, 11, 93, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chunk 版本不兼容");
        verify(repository, never()).compareAndSetPublication(anyLong(), anyInt(), any(),
                anyLong(), anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void retirementRefusesActivePublicationBuildAndRetentionFloor() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.RETIRED)));
        when(repository.isActivePublicationTarget(93L)).thenReturn(true);
        GraphSnapshotRetirementService service = service(repository, epochs);

        assertThatThrownBy(() -> service.retireForCleanup(93))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("发布或构建引用");

        when(repository.isActivePublicationTarget(93L)).thenReturn(false);
        when(repository.hasActiveBuildForSnapshot(93L)).thenReturn(true);
        assertThatThrownBy(() -> service.retireForCleanup(93))
                .hasMessageContaining("发布或构建引用");

        when(repository.hasActiveBuildForSnapshot(93L)).thenReturn(false);
        when(repository.findRecentSnapshotIds(7L, 12, 2)).thenReturn(List.of(95L, 93L));
        assertThatThrownBy(() -> service.retireForCleanup(93))
                .hasMessageContaining("最少保留范围");

        when(repository.findRecentSnapshotIds(7L, 12, 2)).thenReturn(List.of(95L, 94L));
        when(repository.hasActiveReadLeases(93L)).thenReturn(false);
        when(repository.markSnapshotDeleting(93L)).thenReturn(true);
        assertThat(service.retireForCleanup(93)).isTrue();
        verify(repository).markSnapshotDeleting(93L);
    }

    @Test
    void cleanupWaitsForGracePeriodAndReadLeasesBeforeDeleting() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotRetiredAt(93L))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofMinutes(5))));
        when(repository.hasActiveReadLeases(93L)).thenReturn(false);
        GraphSnapshotRetirementService service = service(repository, epochs);
        GraphSnapshotResourceCleanupPort cleaner = mock(GraphSnapshotResourceCleanupPort.class);

        assertThat(service.cleanupEligible(93)).isFalse();
        assertThat(service.cleanupResources(93, cleaner).complete()).isFalse();
        verify(cleaner, never()).delete(any(), any());
    }

    @Test
    void cleanupDeletesResourcesIdempotentlyAndKeepsSnapshotRecoverableOnPartialFailure() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotRetiredAt(93L))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(2))));
        when(repository.hasActiveReadLeases(93L)).thenReturn(false);
        GraphResourceReferenceRecord communityIndex = reference(501, "COMMUNITY_PHYSICAL_INDEX",
                "kwiki-communities-v3-kb7", "ACTIVE");
        GraphResourceReferenceRecord graphPartition = reference(502, "ARCADB_GRAPH_SNAPSHOT",
                "kwiki:43", "ACTIVE");
        when(repository.findPendingResourceReferences(93L))
                .thenReturn(List.of(communityIndex, graphPartition))
                .thenReturn(List.of(graphPartition));
        when(repository.markResourceDeleting(anyLong())).thenReturn(true);
        when(repository.markResourceDeleted(anyLong())).thenReturn(true);
        when(repository.markResourceFailed(anyLong(), any())).thenReturn(true);
        GraphSnapshotRetirementService service = service(repository, epochs);
        GraphSnapshotResourceCleanupPort cleaner = (kind, identity) ->
                "COMMUNITY_PHYSICAL_INDEX".equals(kind);

        GraphSnapshotRetirementService.CleanupResult result = service.cleanupResources(93, cleaner);

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.remaining()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        verify(repository).markResourceDeleted(501L);
        verify(repository).markResourceFailed(eq(502L), any());
        verify(repository, never()).markResourceDeleted(502L);
    }

    private static GraphResourceReferenceRecord reference(long id, String kind, String identity,
                                                          String state) {
        return new GraphResourceReferenceRecord(id, kind, identity, state, null);
    }

    private static GraphSnapshotRetirementService service(GraphBuildRepository repository,
                                                          GraphSourceEpochService epochs) {
        GraphProperties properties = new GraphProperties(false,
                com.kwiki.graph.GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), GraphProperties.Capacity.defaults());
        return new GraphSnapshotRetirementService(repository, epochs, properties);
    }
}
