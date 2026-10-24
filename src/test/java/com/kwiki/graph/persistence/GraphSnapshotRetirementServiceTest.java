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
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.DELETING)));
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
        // 快照创建时没有登记资源：清理前先补齐
        verify(repository).ensureSnapshotResources(93L);
    }

    @Test
    void cleanupDeletesTheSnapshotGraphVersionAndCommunityIndexThroughTheSharedCleaner() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotRetiredAt(93L))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(2))));
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.DELETING)));
        GraphResourceReferenceRecord graph = reference(601, GraphArtifactCleaner.KIND_GRAPH_VERSION, "7:43", "ACTIVE");
        GraphResourceReferenceRecord index = reference(602, GraphArtifactCleaner.KIND_COMMUNITY_INDEX,
                "kwiki-communities-v3-kb7", "ACTIVE");
        when(repository.findPendingResourceReferences(93L))
                .thenReturn(List.of(graph, index))
                .thenReturn(List.of());
        GraphVersionDataCleaner graphCleaner = mock(GraphVersionDataCleaner.class);
        com.kwiki.indexing.search.ElasticsearchIndexManager indexes =
                mock(com.kwiki.indexing.search.ElasticsearchIndexManager.class);

        GraphSnapshotRetirementService.CleanupResult result = service(repository, epochs)
                .cleanupResources(93, new GraphArtifactCleaner(graphCleaner, indexes));

        assertThat(result.complete()).isTrue();
        assertThat(result.deleted()).isEqualTo(2);
        verify(graphCleaner).deleteGraphVersion(7L, 43L);
        try {
            verify(indexes).forceDeleteManagedIndex("kwiki-communities-v3-kb7");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        verify(repository).markResourceDeleted(601L);
        verify(repository).markResourceDeleted(602L);
    }

    @Test
    void cleanupRecordsTheFailureReasonForRetry() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotRetiredAt(93L))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(2))));
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.DELETING)));
        GraphResourceReferenceRecord graph = reference(601, GraphArtifactCleaner.KIND_GRAPH_VERSION, "7:43", "ACTIVE");
        when(repository.findPendingResourceReferences(93L)).thenReturn(List.of(graph));
        GraphVersionDataCleaner graphCleaner = mock(GraphVersionDataCleaner.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("arcadedb down"))
                .when(graphCleaner).deleteGraphVersion(7L, 43L);

        var result = service(repository, epochs).cleanupResources(93, new GraphArtifactCleaner(graphCleaner, null));

        assertThat(result.complete()).isFalse();
        verify(repository).markResourceFailed(601L, "arcadedb down");
    }

    @Test
    void snapshotThatIsNotRetiredOrStillPublishedIsNeverCleaned() {
        for (boolean published : List.of(false, true)) {
            GraphBuildRepository repository = mock(GraphBuildRepository.class);
            GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
            when(repository.findSnapshotRetiredAt(93L))
                    .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(2))));
            // 未进入 DELETING（如仅退役为 RETIRED），或仍是发布指针目标
            when(repository.findSnapshotById(93L)).thenReturn(Optional.of(new GraphSnapshotEntry(SNAPSHOT,
                    published ? GraphSnapshotState.DELETING : GraphSnapshotState.RETIRED)));
            when(repository.isActivePublicationTarget(93L)).thenReturn(published);
            GraphSnapshotResourceCleanupPort cleaner = mock(GraphSnapshotResourceCleanupPort.class);

            assertThat(service(repository, epochs).cleanupResources(93, cleaner).complete()).isFalse();
            verify(repository, never()).ensureSnapshotResources(anyLong());
            verify(cleaner, never()).delete(any(), any());
        }
    }

    @Test
    void rollbackRejectsSnapshotReferencingPhysicallyDeletedPage() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.RETIRED)));
        when(repository.findManifestResourceIds(93L))
                .thenReturn(List.of(com.kwiki.graph.GraphResourceId.page(9)));
        var purged = java.util.Set.of(com.kwiki.graph.GraphResourceId.page(9));
        GraphSnapshotRetirementService service = service(repository, epochs, purged);

        assertThatThrownBy(() -> service.rollback(7, 12, 93, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("物理删除");
        verify(repository, never()).compareAndSetPublication(anyLong(), anyInt(), any(),
                anyLong(), anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void rollbackAllowsSnapshotReferencingOnlyAnArchivedPage() {
        // 已归档但仍在回收站的页面行仍存在：不阻止回滚，查询期计为已退役来源
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.RETIRED)));
        when(repository.findManifestResourceIds(93L))
                .thenReturn(List.of(com.kwiki.graph.GraphResourceId.page(9)));
        when(epochs.currentForUpdate(7)).thenReturn(new long[]{6, 3});
        when(repository.compareAndSetPublication(eq(7L), eq(12), eq(91L), eq(93L),
                eq(6L), eq(3L), any(Instant.class))).thenReturn(true);
        GraphSnapshotRetirementService service = service(repository, epochs);

        assertThat(service.rollback(7, 12, 93, 91L)).isTrue();
    }

    @Test
    void retentionExemptionIsSkippedForSnapshotReferencingPurgedSources() {
        // 引用已物理删除来源的快照即使处于「当前/上一版本」保留范围也可退役清理
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(repository.findSnapshotById(93L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(SNAPSHOT, GraphSnapshotState.RETIRED)));
        when(repository.isActivePublicationTarget(93L)).thenReturn(false);
        when(repository.hasActiveBuildForSnapshot(93L)).thenReturn(false);
        when(repository.findRecentSnapshotIds(7L, 12, 2)).thenReturn(List.of(95L, 93L));
        when(repository.findManifestResourceIds(93L))
                .thenReturn(List.of(com.kwiki.graph.GraphResourceId.page(9)));
        when(repository.hasActiveReadLeases(93L)).thenReturn(false);
        when(repository.markSnapshotDeleting(93L)).thenReturn(true);
        var purged = java.util.Set.of(com.kwiki.graph.GraphResourceId.page(9));
        GraphSnapshotRetirementService service = service(repository, epochs, purged);

        assertThat(service.retireForCleanup(93)).isTrue();
        verify(repository).markSnapshotDeleting(93L);
    }

    private static GraphResourceReferenceRecord reference(long id, String kind, String identity,
                                                          String state) {
        return new GraphResourceReferenceRecord(id, kind, identity, state, null);
    }

    private static GraphSnapshotRetirementService service(GraphBuildRepository repository,
                                                          GraphSourceEpochService epochs) {
        return service(repository, epochs, java.util.Set.of());
    }

    /** inventory 资源存在性：purged 中的资源视为已物理删除，其余存在。 */
    private static GraphSnapshotRetirementService service(GraphBuildRepository repository,
                                                          GraphSourceEpochService epochs,
                                                          java.util.Set<com.kwiki.graph.GraphResourceId> purged) {
        GraphProperties properties = new GraphProperties(false,
                com.kwiki.graph.GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), GraphProperties.Capacity.defaults());
        com.kwiki.graph.GraphSourceInventoryPort inventory = new com.kwiki.graph.GraphSourceInventoryPort() {
            @Override
            public java.util.List<com.kwiki.graph.GraphResourceId> listCurrentSources(long kbId) {
                return java.util.List.of();
            }

            @Override
            public boolean canRead(long userId, boolean superuser, com.kwiki.graph.GraphResourceId resource) {
                return true;
            }

            @Override
            public long[] currentEpochs(long kbId) {
                return new long[]{5, 2};
            }

            @Override
            public java.util.Map<com.kwiki.graph.GraphResourceId, com.kwiki.graph.GraphSourceInventoryPort.LifecycleState> lifecycleStates(
                    java.util.List<com.kwiki.graph.GraphResourceId> resources) {
                java.util.Map<com.kwiki.graph.GraphResourceId, com.kwiki.graph.GraphSourceInventoryPort.LifecycleState> states =
                        new java.util.LinkedHashMap<>();
                for (com.kwiki.graph.GraphResourceId resource : resources) {
                    states.put(resource, purged.contains(resource)
                            ? com.kwiki.graph.GraphSourceInventoryPort.LifecycleState.RETIRED
                            : com.kwiki.graph.GraphSourceInventoryPort.LifecycleState.LIVE);
                }
                return states;
            }

            @Override
            public boolean exists(com.kwiki.graph.GraphResourceId resource) {
                return !purged.contains(resource);
            }
        };
        return new GraphSnapshotRetirementService(repository, epochs, inventory, properties);
    }
}
