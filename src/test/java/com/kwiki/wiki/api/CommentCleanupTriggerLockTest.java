package com.kwiki.wiki.api;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论清理触发器的锁语义：锁不可得（他实例持有或锁服务不可用）时
 * 跳过本轮且不执行任何清理；持锁时按游标推进。全部 mock，无外部连接。
 */
class CommentCleanupTriggerLockTest {

    private CommentCleanupStrategy strategy;
    private JdbcOperations jdbc;
    private KwikiDistributedLocks locks;
    private CommentCleanupTrigger trigger;

    @BeforeEach
    void setUp() {
        strategy = mock(CommentCleanupStrategy.class);
        jdbc = mock(JdbcOperations.class);
        locks = mock(KwikiDistributedLocks.class);
        trigger = new CommentCleanupTrigger(strategy,
                StandardTestProperties.providerOf(jdbc),
                StandardTestProperties.nullProvider(),
                locks);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
    }

    @Test
    void lockHolderAdvancesTheCursor() {
        when(locks.tryRun(eq("comment-cleanup"), eq(Duration.ZERO), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return true;
                });
        when(strategy.cleanupBatch(anyInt(), anyLong()))
                .thenReturn(new CommentCleanupStrategy.CleanupBatch(0, 42L));

        trigger.daily();

        verify(strategy).cleanupBatch(500, 42L);
    }

    @Test
    void busyOrUnavailableLockSkipsTheRunWithoutCleanup() {
        when(locks.tryRun(anyString(), any(Duration.class), any(Runnable.class))).thenReturn(false);

        trigger.daily();

        verify(strategy, never()).cleanupBatch(anyInt(), anyLong());
    }

    @Test
    void missingJdbcSkipsEntirely() {
        CommentCleanupTrigger offline = new CommentCleanupTrigger(strategy,
                StandardTestProperties.nullProvider(),
                StandardTestProperties.nullProvider(),
                locks);

        offline.daily();

        verify(locks, never()).tryRun(anyString(), any(Duration.class), any(Runnable.class));
    }
}
