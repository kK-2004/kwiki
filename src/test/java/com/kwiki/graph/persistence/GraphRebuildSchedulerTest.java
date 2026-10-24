package com.kwiki.graph.persistence;

import com.kwiki.graph.config.GraphRuntimeSettings;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.indexing.version.SearchIndexVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 去抖自动重建调度：静默期内多次编辑只提交一次、最长延迟强制提交、
 * 有索引任务或未建图不提交、锁不可用跳过、发布覆盖后清除、STALE 退避重提。
 */
class GraphRebuildSchedulerTest {

    private static final long KB = 7;
    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");

    private static SearchIndexVersion selected() {
        return SearchIndexVersion.bootstrapped(3, "kwiki-chunks-v3",
                new com.kwiki.indexing.version.EditableIndexConfig(
                        "kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024,
                        "kwiki-schema-1"), "hash");
    }

    private static GraphRebuildRequestRepository.GraphRebuildRequestRecord record(
            Instant firstDirty, Instant lastDirty, boolean hardDelete,
            Long lastBatchId, int attempts) {
        return new GraphRebuildRequestRepository.GraphRebuildRequestRecord(
                KB, firstDirty, lastDirty, 5, 2, hardDelete, lastBatchId,
                lastBatchId == null ? null : "SUBMITTED", attempts, null);
    }

    private record Deps(GraphRebuildRequestRepository requests,
                        com.kwiki.graph.persistence.GraphBuildTargets targets,
                        GraphBuildBatchService batches,
                        KwikiDistributedLocks locks,
                        SimpleMeterRegistry registry,
                        GraphRebuildScheduler scheduler) {
    }

    private static Deps deps(GraphRebuildRequestRepository.GraphRebuildRequestRecord... rows) {
        GraphRebuildRequestRepository requests = mock(GraphRebuildRequestRepository.class);
        com.kwiki.graph.persistence.GraphBuildTargets targets =
                mock(com.kwiki.graph.persistence.GraphBuildTargets.class);
        GraphBuildBatchService batches = mock(GraphBuildBatchService.class);
        KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        when(requests.findAll()).thenReturn(List.of(rows));
        when(requests.knowledgeBaseActive(KB)).thenReturn(true);
        when(targets.selectedVersion()).thenReturn(java.util.Optional.of(selected()));
        when(locks.isAvailable()).thenReturn(true);
        // 锁语义与生产一致：获取成功才执行扫描
        when(locks.tryRunReturning(eq("graph:rebuild"), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<Integer> body = invocation.getArgument(2);
                    return body.get();
                });
        when(batches.submit(any())).thenReturn(new GraphBuildSubmission(55L, 1L, List.of(77L), false));

        GraphRuntimeSettings settings = new GraphRuntimeSettings(false, true, "30m", "6h", true);
        GraphRebuildScheduler scheduler = new GraphRebuildScheduler(requests, targets, batches,
                settings, locks, new GraphBuildMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Deps(requests, targets, batches, locks, registry, scheduler);
    }

    private static GraphRebuildRequestRepository.SubmittedRun run(String state, long content,
                                                                  long security) {
        return new GraphRebuildRequestRepository.SubmittedRun(state, content, security);
    }

    /** 解除等待后本轮可直接重提的公共前置：静默期已到、无构建、无索引任务。 */
    private static void stubReadyToSubmit(Deps deps) {
        when(deps.requests().hasActiveBuild(KB)).thenReturn(false);
        when(deps.requests().hasPendingIndexingJobs(KB)).thenReturn(false);
    }

    private static void stubPublished(Deps deps, long contentEpoch, long securityEpoch) {
        when(deps.requests().publishedEpochs(eq(KB), anyInt()))
                .thenReturn(new long[]{contentEpoch, securityEpoch});
    }

    @Test
    void quietPeriodCohortSubmitsOnceAfterSilence() {
        // 多次编辑：last_dirty_at 每次刷新，静默期从最后一次编辑起算；
        // 距最后编辑 40 分钟 → 满足静默期，提交一次
        Deps deps = deps(record(NOW.minus(Duration.ofMinutes(40)),
                NOW.minus(Duration.ofMinutes(40)), false, null, 0));
        stubPublished(deps, 3, 1);
        when(deps.requests().hasActiveBuild(KB)).thenReturn(false);
        when(deps.requests().hasPendingIndexingJobs(KB)).thenReturn(false);

        assertThat(deps.scheduler().runOnce()).isEqualTo(1);

        verify(deps.batches()).submit(any());
        verify(deps.requests()).recordSubmitted(KB, 55L);
        assertThat(deps.registry().counter("graph.rebuild.submitted").count()).isEqualTo(1);
    }

    @Test
    void freshEditsInsideQuietPeriodDoNotSubmit() {
        // 最后一次编辑在 10 分钟前（静默期 30 分钟）：不提交
        Deps deps = deps(record(NOW.minus(Duration.ofHours(2)),
                NOW.minus(Duration.ofMinutes(10)), false, null, 0));
        stubPublished(deps, 3, 1);
        when(deps.requests().hasActiveBuild(KB)).thenReturn(false);
        when(deps.requests().hasPendingIndexingJobs(KB)).thenReturn(false);

        assertThat(deps.scheduler().runOnce()).isZero();
        verify(deps.batches(), never()).submit(any());
    }

    @Test
    void maximumDelayForcesSubmissionDespiteContinuousEditing() {
        // 持续编辑从未静默，但首次变化已超过 6 小时：仍提交
        Deps deps = deps(record(NOW.minus(Duration.ofHours(7)),
                NOW.minus(Duration.ofMinutes(5)), false, null, 0));
        stubPublished(deps, 3, 1);
        when(deps.requests().hasActiveBuild(KB)).thenReturn(false);
        when(deps.requests().hasPendingIndexingJobs(KB)).thenReturn(false);

        assertThat(deps.scheduler().runOnce()).isEqualTo(1);
        verify(deps.batches()).submit(any());
    }

    @Test
    void pendingIndexingJobsDelaySubmission() {
        // 静默期已到但该库仍有排队中的索引任务：本轮不提交
        Deps deps = deps(record(NOW.minus(Duration.ofMinutes(40)),
                NOW.minus(Duration.ofMinutes(40)), false, null, 0));
        stubPublished(deps, 3, 1);
        when(deps.requests().hasActiveBuild(KB)).thenReturn(false);
        when(deps.requests().hasPendingIndexingJobs(KB)).thenReturn(true);

        assertThat(deps.scheduler().runOnce()).isZero();
        verify(deps.batches(), never()).submit(any());
    }

    @Test
    void knowledgeBaseWithoutPublishedSnapshotIsNeverAutoBuilt() {
        // 从未建图的库（当前选中版本下无发布快照）：不自动提交
        Deps deps = deps(record(NOW.minus(Duration.ofHours(7)),
                NOW.minus(Duration.ofMinutes(40)), false, null, 0));
        when(deps.requests().publishedEpochs(eq(KB), anyInt())).thenReturn(null);
        when(deps.requests().hasActiveBuild(KB)).thenReturn(false);
        when(deps.requests().hasPendingIndexingJobs(KB)).thenReturn(false);

        assertThat(deps.scheduler().runOnce()).isZero();
        verify(deps.batches(), never()).submit(any());
    }

    @Test
    void hardDeleteSkipsTheQuietPeriod() {
        Deps deps = deps(record(NOW.minus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofMinutes(1)), true, null, 0));
        stubPublished(deps, 3, 1);
        when(deps.requests().hasActiveBuild(KB)).thenReturn(false);
        when(deps.requests().hasPendingIndexingJobs(KB)).thenReturn(false);

        assertThat(deps.scheduler().runOnce()).isEqualTo(1);
        verify(deps.batches()).submit(any());
    }

    @Test
    void publishedSnapshotCoveringDirtyEpochsClearsTheRequest() {
        Deps deps = deps(record(NOW.minus(Duration.ofMinutes(40)),
                NOW.minus(Duration.ofMinutes(40)), false, null, 0));
        stubPublished(deps, 5, 2);

        assertThat(deps.scheduler().runOnce()).isZero();
        verify(deps.requests()).clear(KB);
        verify(deps.batches(), never()).submit(any());
    }

    @Test
    void staleBuildIsRecordedAndRetriedAfterBackoff() {
        Deps deps = deps(record(NOW.minus(Duration.ofHours(7)),
                NOW.minus(Duration.ofMinutes(40)), false, 55L, 1));
        stubPublished(deps, 3, 1);
        when(deps.requests().submittedRun(55L, KB)).thenReturn(run("STALE", 5, 2));

        assertThat(deps.scheduler().runOnce()).isZero();
        verify(deps.requests()).recordOutcome(eq(KB), eq("STALE"),
                eq(NOW.plus(Duration.ofMinutes(30))));
        assertThat(deps.registry().counter("graph.rebuild.stale").count()).isEqualTo(1);
        verify(deps.batches(), never()).submit(any());
    }

    @Test
    void lockNotAcquiredSkipsTheWholeRound() {
        Deps deps = deps(record(NOW.minus(Duration.ofMinutes(40)),
                NOW.minus(Duration.ofMinutes(40)), false, null, 0));
        stubPublished(deps, 3, 1);
        when(deps.locks().tryRunReturning(eq("graph:rebuild"), any(Duration.class), any()))
                .thenReturn(null);

        assertThat(deps.scheduler().runOnce()).isZero();
        verify(deps.batches(), never()).submit(any());
    }

    @Test
    void disabledRebuildStopsScanning() {
        // Nacos 将 rebuild.enabled 改为 false：下一轮起不再扫描，待重建记录保留
        GraphRebuildRequestRepository requests = mock(GraphRebuildRequestRepository.class);
        KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
        when(locks.isAvailable()).thenReturn(true);
        GraphRuntimeSettings settings = new GraphRuntimeSettings(false, false, "30m", "6h", true);
        GraphRebuildScheduler scheduler = new GraphRebuildScheduler(requests,
                mock(com.kwiki.graph.persistence.GraphBuildTargets.class),
                mock(GraphBuildBatchService.class), settings, locks,
                new GraphBuildMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(scheduler.runOnce()).isZero();
        verify(requests, never()).findAll();
    }

    @Test
    void rebuildKeyUsesDirtyEpochsAndDistinguishesRetries() {
        var first = record(NOW, NOW, false, null, 0);
        var retry = record(NOW, NOW, false, null, 2);
        assertThat(GraphRebuildScheduler.rebuildKey(first))
                .isEqualTo("GRAPH_REBUILD:7:5:2");
        assertThat(GraphRebuildScheduler.rebuildKey(retry))
                .isEqualTo("GRAPH_REBUILD:7:5:2#retry2");
    }

    @Test
    void backoffGrowsExponentiallyAndIsCappedAtMaxDelay() {
        GraphRuntimeSettings.Snapshot config =
                new GraphRuntimeSettings(false, true, "30m", "6h", true).snapshot();
        assertThat(GraphRebuildScheduler.backoff(0, config)).isEqualTo(Duration.ofMinutes(30));
        assertThat(GraphRebuildScheduler.backoff(1, config)).isEqualTo(Duration.ofMinutes(30));
        assertThat(GraphRebuildScheduler.backoff(2, config)).isEqualTo(Duration.ofHours(1));
        assertThat(GraphRebuildScheduler.backoff(3, config)).isEqualTo(Duration.ofHours(2));
        assertThat(GraphRebuildScheduler.backoff(9, config)).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void publishedBatchNotCoveringNewerEditsIsReleasedAndResubmitted() {
        // 批次 55 已发布（当时 epoch 4/2），发布后又有编辑 → dirty 5/2，发布快照 4/2 不覆盖
        Deps deps = deps(record(NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(40)), false, 55L, 1));
        stubPublished(deps, 4, 2);
        when(deps.requests().submittedRun(55L, KB)).thenReturn(run("PUBLISHED", 4, 2));
        stubReadyToSubmit(deps);

        assertThat(deps.scheduler().runOnce()).isEqualTo(1);

        verify(deps.requests()).releaseSubmission(KB);
        verify(deps.requests(), never()).recordOutcome(eq(KB), anyString(), any());
        verify(deps.batches()).submit(any());
    }

    @Test
    void readyCandidateCoveringDirtyEpochsWaitsForManualPublish() {
        // rebuild.auto-publish 关闭：候选 READY 且已覆盖 dirty 5/2，等待管理员发布
        Deps deps = deps(record(NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(40)), false, 55L, 1));
        stubPublished(deps, 3, 1);
        when(deps.requests().submittedRun(55L, KB)).thenReturn(run("READY", 5, 2));

        assertThat(deps.scheduler().runOnce()).isZero();

        verify(deps.requests(), never()).releaseSubmission(anyLong());
        verify(deps.batches(), never()).submit(any());
    }

    @Test
    void outdatedReadyCandidateIsReleasedAndResubmitted() {
        // 候选 READY 时捕获 4/2，其后又有编辑 → dirty 5/2，候选已过期
        Deps deps = deps(record(NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(40)), false, 55L, 1));
        stubPublished(deps, 3, 1);
        when(deps.requests().submittedRun(55L, KB)).thenReturn(run("READY", 4, 2));
        stubReadyToSubmit(deps);

        assertThat(deps.scheduler().runOnce()).isEqualTo(1);

        verify(deps.requests()).releaseSubmission(KB);
        verify(deps.batches()).submit(any());
    }

    @Test
    void missingSubmittedRunIsReleased() {
        Deps deps = deps(record(NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(40)), false, 55L, 1));
        stubPublished(deps, 3, 1);
        when(deps.requests().submittedRun(55L, KB)).thenReturn(null);
        stubReadyToSubmit(deps);

        assertThat(deps.scheduler().runOnce()).isEqualTo(1);
        verify(deps.requests()).releaseSubmission(KB);
    }

    @Test
    void buildStillRunningOrWaitingForOperatorKeepsWaiting() {
        for (String state : List.of("EXTRACTING", "WAITING_FOR_CHUNKS", "NEEDS_ATTENTION")) {
            Deps deps = deps(record(NOW.minus(Duration.ofHours(1)),
                    NOW.minus(Duration.ofMinutes(40)), false, 55L, 1));
            stubPublished(deps, 3, 1);
            when(deps.requests().submittedRun(55L, KB)).thenReturn(run(state, 5, 2));

            assertThat(deps.scheduler().runOnce()).as(state).isZero();
            verify(deps.requests(), never()).releaseSubmission(anyLong());
            verify(deps.requests(), never()).recordOutcome(anyLong(), anyString(), any());
            verify(deps.batches(), never()).submit(any());
        }
    }

    @Test
    void partialFailureIsRecordedAsFailedWithBackoff() {
        Deps deps = deps(record(NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(40)), false, 55L, 1));
        stubPublished(deps, 3, 1);
        when(deps.requests().submittedRun(55L, KB)).thenReturn(run("PARTIAL_FAILED", 5, 2));

        assertThat(deps.scheduler().runOnce()).isZero();
        verify(deps.requests()).recordOutcome(eq(KB), eq("FAILED"),
                eq(NOW.plus(Duration.ofMinutes(30))));
    }

    @Test
    void archivedOrDeletedKnowledgeBaseClearsTheRequestWithoutSubmitting() {
        Deps deps = deps(record(NOW.minus(Duration.ofHours(7)),
                NOW.minus(Duration.ofMinutes(40)), true, null, 0));
        when(deps.requests().knowledgeBaseActive(KB)).thenReturn(false);
        stubPublished(deps, 3, 1);
        stubReadyToSubmit(deps);

        assertThat(deps.scheduler().runOnce()).isZero();

        verify(deps.requests()).clear(KB);
        verify(deps.batches(), never()).submit(any());
        verify(deps.requests(), never()).publishedEpochs(anyLong(), anyInt());
    }
}
