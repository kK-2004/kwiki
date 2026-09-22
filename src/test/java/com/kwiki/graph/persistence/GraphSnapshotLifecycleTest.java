package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import com.kwiki.graph.GraphSnapshotEpochGuard;
import com.kwiki.graph.GraphSnapshotPin;
import com.kwiki.graph.config.GraphProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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

/**
 * 9.6 发布生命周期验证：发布前后崩溃、并发内容/权限变化、旧请求跨发布
 * 继续读取、过期摘要回滚禁用及清理与新 pin 竞争，任务与请求均不混版。
 */
class GraphSnapshotLifecycleTest {

    private static final GraphSnapshot V3_SNAPSHOT = new GraphSnapshot(91, 7, 42, 3, 5,
            "kwiki-chunks-v3", "kwiki-communities-v5-kb7", "entity-linking-v1",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 5, 2);
    private static final GraphSnapshot V4_SNAPSHOT = new GraphSnapshot(95, 7, 43, 4, 6,
            "kwiki-chunks-v4", "kwiki-communities-v6-kb7", "entity-linking-v1",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 6, 3);
    private static final GraphValidationChecklist CHECKLIST = new GraphValidationChecklist(
            true, true, true, true, true, true, true);

    @Test
    void crashBeforePointerCommitLeavesOldPairingActiveAndCandidateRecoverable() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = epochService(6, 3);
        when(repository.compareAndSetPublication(eq(7L), eq(4), any(), eq(95L),
                eq(6L), eq(3L), any(Instant.class))).thenReturn(false);
        GraphSnapshotPublicationService publication =
                new GraphSnapshotPublicationService(repository, epochs);

        // 指针 CAS 未提交（崩溃或竞争失败），候选不发布。
        assertThat(publication.publish(V4_SNAPSHOT, GraphSnapshotState.READY,
                CHECKLIST, null)).isFalse();

        GraphSnapshotReadService reader = readService(repository);
        when(repository.findActivePublicationSnapshot(7, 4)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reader.pin(7, 4))
                .hasMessageContaining("没有已发布的图快照");

        // 旧配对继续在线可读；候选数据保留，可恢复发布或清理。
        when(repository.findActivePublicationSnapshot(7, 3)).thenReturn(Optional.of(
                new GraphSnapshotEntry(V3_SNAPSHOT, GraphSnapshotState.PUBLISHED)));
        when(repository.acquireReadLease(eq(91L), any(Instant.class))).thenReturn(555L);
        assertThat(reader.pin(7, 3).snapshot().snapshotId()).isEqualTo(91);
    }

    @Test
    void concurrentContentOrPermissionChangeBlocksPublishAndKeepsOnlinePointer() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = epochService(7, 4);
        GraphSnapshotPublicationService publication =
                new GraphSnapshotPublicationService(repository, epochs);

        assertThatThrownBy(() -> publication.publish(V4_SNAPSHOT, GraphSnapshotState.READY,
                CHECKLIST, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("epoch 已过期");
        verify(repository, never()).compareAndSetPublication(anyLong(), anyInt(), any(),
                anyLong(), anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void invalidChecklistNeverReachesPointerUpdate() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = epochService(6, 3);
        GraphSnapshotPublicationService publication =
                new GraphSnapshotPublicationService(repository, epochs);
        GraphValidationChecklist broken = new GraphValidationChecklist(
                true, true, false, true, true, true, true);

        assertThatThrownBy(() -> publication.publish(V4_SNAPSHOT, GraphSnapshotState.READY,
                broken, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("校验未通过");
        verify(repository, never()).compareAndSetPublication(anyLong(), anyInt(), any(),
                anyLong(), anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void inFlightRequestKeepsPinnedPhysicalIndexesAcrossPointerCommit() {
        GraphSnapshot newerV3 = new GraphSnapshot(96, 7, 44, 3, 7,
                "kwiki-chunks-v3", "kwiki-communities-v7-kb7", "entity-linking-v1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 6, 3);
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = epochService(6, 3);
        when(repository.findActivePublicationSnapshot(7, 3)).thenReturn(Optional.of(
                new GraphSnapshotEntry(V3_SNAPSHOT, GraphSnapshotState.PUBLISHED)));
        when(repository.acquireReadLease(eq(91L), any(Instant.class))).thenReturn(555L);
        GraphSnapshotReadService reader = readService(repository);

        GraphSnapshotPin pinnedBeforePublish = reader.pin(7, 3);

        // 同一配对的指针随后提交到新图快照（新 graphVersion/社区版本）。
        when(repository.findActivePublicationSnapshot(7, 3)).thenReturn(Optional.of(
                new GraphSnapshotEntry(newerV3, GraphSnapshotState.PUBLISHED)));
        when(repository.acquireReadLease(eq(96L), any(Instant.class))).thenReturn(556L);
        GraphSnapshotPin pinnedAfterPublish = reader.pin(7, 3);

        assertThat(pinnedBeforePublish.snapshot().communityPhysicalIndex())
                .isEqualTo("kwiki-communities-v5-kb7");
        assertThat(pinnedAfterPublish.snapshot().communityPhysicalIndex())
                .isEqualTo("kwiki-communities-v7-kb7");
        assertThat(pinnedBeforePublish.snapshot().graphVersion()).isEqualTo(42);
        assertThat(pinnedAfterPublish.snapshot().graphVersion()).isEqualTo(44);
    }

    @Test
    void rollbackToEpochStaleSnapshotSucceedsButDisablesSummaries() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = epochService(9, 5);
        when(repository.findSnapshotById(91L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(V3_SNAPSHOT, GraphSnapshotState.RETIRED)));
        when(repository.compareAndSetPublication(eq(7L), eq(3), eq(90L), eq(91L),
                eq(9L), eq(5L), any(Instant.class))).thenReturn(true);
        GraphSnapshotRetirementService retirement = retirementService(repository, epochs);

        assertThat(retirement.rollback(7, 3, 91, 90L)).isTrue();

        // 回滚后快照记录的 epoch 是 (5,2)，与当前 (9,5) 不同：摘要保持禁用，
        // 只有逐条来源核验仍有效的图事实可以继续使用。
        assertThat(GraphSnapshotEpochGuard.summariesUsable(V3_SNAPSHOT, 9, 5)).isFalse();
        assertThat(GraphSnapshotEpochGuard.summariesUsable(V3_SNAPSHOT, 5, 2)).isTrue();
    }

    @Test
    void cleanupRacesWithNewPinsOnBothSides() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSourceEpochService epochs = epochService(9, 5);

        // 已置 DELETING 的快照拒绝新 pin。
        when(repository.findActivePublicationSnapshot(7, 3)).thenReturn(Optional.of(
                new GraphSnapshotEntry(V3_SNAPSHOT, GraphSnapshotState.DELETING)));
        GraphSnapshotReadService reader = readService(repository);
        assertThatThrownBy(() -> reader.pin(7, 3))
                .hasMessageContaining("不再接受新的读取 pin");

        // 仍有读取租约的快照不能进入清理。
        when(repository.findSnapshotById(91L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(V3_SNAPSHOT, GraphSnapshotState.RETIRED)));
        when(repository.isActivePublicationTarget(91L)).thenReturn(false);
        when(repository.hasActiveBuildForSnapshot(91L)).thenReturn(false);
        when(repository.findRecentSnapshotIds(7L, 3, 2)).thenReturn(java.util.List.of(95L, 94L));
        when(repository.hasActiveReadLeases(91L)).thenReturn(true);
        GraphSnapshotRetirementService retirement = retirementService(repository, epochs);
        assertThatThrownBy(() -> retirement.retireForCleanup(91))
                .hasMessageContaining("读取引用");
        verify(repository, never()).markSnapshotDeleting(91L);
    }

    @Test
    void multiKnowledgeBasePinsNeverMixGraphVersions() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshot otherKb = new GraphSnapshot(97, 8, 50, 3, 6,
                "kwiki-chunks-v3", "kwiki-communities-v6-kb8", "entity-linking-v1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 6, 3);
        when(repository.findActivePublicationSnapshot(7, 3)).thenReturn(Optional.of(
                new GraphSnapshotEntry(V3_SNAPSHOT, GraphSnapshotState.PUBLISHED)));
        when(repository.findActivePublicationSnapshot(8, 3)).thenReturn(Optional.of(
                new GraphSnapshotEntry(otherKb, GraphSnapshotState.PUBLISHED)));
        when(repository.acquireReadLease(anyLong(), any(Instant.class))).thenReturn(555L, 556L);
        GraphSnapshotReadService reader = readService(repository);

        Map<Long, Optional<GraphSnapshotPin>> pins = reader.pinAll(Map.of(7L, 3L, 8L, 3L));

        assertThat(pins.get(7L).orElseThrow().snapshot().graphVersion()).isEqualTo(42);
        assertThat(pins.get(8L).orElseThrow().snapshot().graphVersion()).isEqualTo(50);
        assertThat(pins.get(7L).orElseThrow().snapshot().communityPhysicalIndex())
                .isNotEqualTo(pins.get(8L).orElseThrow().snapshot().communityPhysicalIndex());
    }

    private static GraphSourceEpochService epochService(long contentEpoch, long securityEpoch) {
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(epochs.currentForUpdate(anyLong())).thenReturn(new long[]{contentEpoch, securityEpoch});
        return epochs;
    }

    private static GraphSnapshotReadService readService(GraphBuildRepository repository) {
        GraphProperties properties = properties();
        return new GraphSnapshotReadService(repository, properties);
    }

    private static GraphSnapshotRetirementService retirementService(
            GraphBuildRepository repository, GraphSourceEpochService epochs) {
        return new GraphSnapshotRetirementService(repository, epochs, properties());
    }

    private static GraphProperties properties() {
        return new GraphProperties(false,
                com.kwiki.graph.GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), GraphProperties.Capacity.defaults());
    }
}
