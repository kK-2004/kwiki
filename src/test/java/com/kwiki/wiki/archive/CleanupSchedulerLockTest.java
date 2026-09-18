package com.kwiki.wiki.archive;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度清理的分布式锁语义：持锁实例执行、锁繁忙/锁服务不可用的
 * 实例跳过本轮且不删除任何数据；锁内故障不逃出调度线程。
 * 全部使用 mock，无外部连接。
 */
class CleanupSchedulerLockTest {

    private final RecycleBinCleanupService cleanup = mock(RecycleBinCleanupService.class);
    private final KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);

    private RecycleBinCleanupScheduler scheduler() {
        return new RecycleBinCleanupScheduler(cleanup, locks);
    }

    @Test
    void lockHolderRunsTheCleanupWithThePreGeneratedJobId() {
        when(cleanup.newJobId()).thenReturn("job-1");
        when(locks.tryRun(eq("recycle-bin-cleanup"), eq(Duration.ZERO), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return true;
                });

        scheduler().daily();

        verify(cleanup).runOnce("job-1");
    }

    @Test
    void busyOrUnavailableLockSkipsTheRunWithoutTouchingData() {
        when(cleanup.newJobId()).thenReturn("job-2");
        when(locks.tryRun(anyString(), any(Duration.class), any(Runnable.class))).thenReturn(false);

        scheduler().daily();

        verify(cleanup, never()).runOnce(any());
    }

    @Test
    void cleanupFailureInsideTheLockDoesNotEscapeTheScheduler() {
        when(cleanup.newJobId()).thenReturn("job-3");
        when(locks.tryRun(eq("recycle-bin-cleanup"), eq(Duration.ZERO), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return true;
                });
        when(cleanup.runOnce("job-3")).thenThrow(new IllegalStateException("db down"));

        scheduler().daily(); // 调度器吞掉主体故障：本轮记日志后结束

        verify(cleanup).runOnce("job-3");
    }
}
