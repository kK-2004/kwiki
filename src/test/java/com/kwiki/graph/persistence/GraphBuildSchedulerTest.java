package com.kwiki.graph.persistence;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 12.6 调度与互斥验证（可控时钟/故障注入，不等待真实 02:00）：
 * 跨日调度、漏跑补偿、手动/定时碰撞、远端未知状态与失锁接管。
 */
class GraphBuildSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private GraphBuildRepository repository = mock(GraphBuildRepository.class);
    private GraphScheduledBatchFactory factory = mock(GraphScheduledBatchFactory.class);
    private GraphBuildBatchService batches = mock(GraphBuildBatchService.class);
    private KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);

    private GraphBuildScheduler scheduler(Instant now) {
        return new GraphBuildScheduler(batches, repository, factory, locks,
                "0 0 2 * * *", "Asia/Shanghai",
                Clock.fixed(now, ZONE));
    }

    private static Instant at(int hour, int minute) {
        return LocalDate.of(2026, 9, 22).atTime(hour, minute).atZone(ZONE).toInstant();
    }

    private void locksAvailable(boolean available) {
        when(locks.isAvailable()).thenReturn(available);
        when(locks.tryRunReturning(eq("graph:schedule"), any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> body = invocation.getArgument(2);
            return body.get();
        });
    }

    @Test
    void scheduledTriggerCreatesBatchOncePerDayAcrossInstances() {
        GraphBuildScheduler scheduler = scheduler(at(2, 0));
        locksAvailable(true);
        when(repository.findScheduleTrigger(LocalDate.of(2026, 9, 22)))
                .thenReturn(Optional.empty());
        when(repository.findActiveBatchId()).thenReturn(Optional.empty());
        when(repository.insertScheduleTrigger(LocalDate.of(2026, 9, 22), "TRIGGERED", null))
                .thenReturn(true);
        GraphBuildBatchRequest request = mock(GraphBuildBatchRequest.class);
        when(factory.create(LocalDate.of(2026, 9, 22))).thenReturn(Optional.of(request));

        assertThat(scheduler.triggerOnce(false)).isEqualTo(
                GraphBuildScheduler.Outcome.TRIGGERED);
        verify(batches).submit(request);

        // 第二个实例同日再次触发：日期记录已存在，不再创建。
        when(repository.findScheduleTrigger(LocalDate.of(2026, 9, 22)))
                .thenReturn(Optional.of(new GraphScheduleTriggerRecord(
                        LocalDate.of(2026, 9, 22), "TRIGGERED", 1L)));
        assertThat(scheduler.triggerOnce(false)).isEqualTo(
                GraphBuildScheduler.Outcome.ALREADY_DONE);
        verify(batches).submit(request);
    }

    @Test
    void unfinishedBatchSkipsTodayWithSkippedActiveAndLinkedBatch() {
        GraphBuildScheduler scheduler = scheduler(at(2, 0));
        locksAvailable(true);
        LocalDate today = LocalDate.of(2026, 9, 22);
        when(repository.findScheduleTrigger(today)).thenReturn(Optional.empty());
        when(repository.findActiveBatchId()).thenReturn(Optional.of(77L));
        when(repository.insertScheduleTrigger(today, "SKIPPED_ACTIVE", 77L)).thenReturn(true);
        when(repository.findScheduleTrigger(today)).thenReturn(Optional.of(
                new GraphScheduleTriggerRecord(today, "SKIPPED_ACTIVE", 77L)));

        assertThat(scheduler.triggerOnce(false)).isEqualTo(
                GraphBuildScheduler.Outcome.SKIPPED_ACTIVE);
        verify(batches, never()).submit(any());
        verify(factory, never()).create(any());

        // 当天不会因漏跑补偿再次尝试。
        assertThat(scheduler.catchUpIfMissed()).isEqualTo(
                GraphBuildScheduler.Outcome.ALREADY_DONE);
    }

    @Test
    void missedDailyRunIsCompensatedAtMostOnceAfterTriggerTime() {
        // 应用 03:30 才恢复：02:00 已过且当天无记录 → 补跑一次。
        GraphBuildScheduler late = scheduler(at(3, 30));
        locksAvailable(true);
        LocalDate today = LocalDate.of(2026, 9, 22);
        when(repository.findScheduleTrigger(today)).thenReturn(Optional.empty());
        when(repository.findActiveBatchId()).thenReturn(Optional.empty());
        when(repository.insertScheduleTrigger(today, "CATCH_UP", null)).thenReturn(true);
        when(factory.create(today)).thenReturn(Optional.empty());

        assertThat(late.catchUpIfMissed()).isEqualTo(GraphBuildScheduler.Outcome.CATCH_UP);
        verify(repository).insertScheduleTrigger(today, "CATCH_UP", null);

        // 01:00 恢复：触发时刻未到，不补跑（也没有新的触发记录写入）。
        GraphBuildScheduler early = scheduler(at(1, 0));
        assertThat(early.catchUpIfMissed()).isEqualTo(
                GraphBuildScheduler.Outcome.ALREADY_DONE);
        verify(repository, never()).insertScheduleTrigger(
                any(), eq("TRIGGERED"), any());
        verify(repository, never()).insertScheduleTrigger(
                any(), eq("SKIPPED_ACTIVE"), any());
    }

    @Test
    void unavailableLockServiceFailsClosedWithoutBatchCreation() {
        GraphBuildScheduler scheduler = scheduler(at(2, 0));
        locksAvailable(false);

        assertThat(scheduler.triggerOnce(false)).isEqualTo(
                GraphBuildScheduler.Outcome.LOCK_NOT_ACQUIRED);
        verify(repository, never()).insertScheduleTrigger(any(), any(), any());
        verify(batches, never()).submit(any());
    }

    @Test
    void leaseLossIsRejectedByFencingOnCheckpoint() {
        // 失锁旧 worker：fencing token 已推进，checkpoint 必须失败。
        var repository = mock(GraphBuildRepository.class);
        when(repository.checkpoint(11L, "worker-old", 3L,
                GraphBuildState.CLUSTERING, GraphBuildStage.CLUSTERING, 27L))
                .thenReturn(false);
        var coordinator = new GraphBuildRunCoordinator(repository);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.checkpoint(
                        runRecord(11L, "worker-old", 3L, GraphBuildState.PROJECTING,
                                GraphBuildStage.PROJECTING),
                        "worker-old", 3L, GraphBuildState.CLUSTERING,
                        GraphBuildStage.CLUSTERING, 27L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fencing");
    }

    @Test
    void globalBuildSlotAllowsOneExecutionAndReportsBusy() {
        KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
        when(locks.isAvailable()).thenReturn(true);
        AtomicInteger executions = new AtomicInteger();
        when(locks.tryRunReturning(eq(GraphBuildSlotService.SLOT_LOCK), any(), any()))
                .thenAnswer(invocation -> {
                    executions.incrementAndGet();
                    return "done";
                })
                .thenReturn(null);
        GraphBuildSlotService slot = new GraphBuildSlotService(locks);

        assertThat(slot.withBuildSlot(() -> "done")).contains("done");
        assertThat(slot.withBuildSlot(() -> "second")).isEmpty();
        assertThat(slot.withBuildSlot(() -> "third")).isEmpty();
        assertThat(executions.get()).isEqualTo(1);
    }

    private static GraphBuildRunRecord runRecord(long id, String owner, long fencing,
                                                 GraphBuildState state,
                                                 GraphBuildStage stage) {
        return new GraphBuildRunRecord(id, 1, 7, 3, "kwiki-chunks-v3", 5,
                "kwiki-communities-v5-kb7", 42, 3, "entity-linking-v1", 5, 2,
                state, stage, fencing, owner, null, 27);
    }
}
