package com.kwiki.graph.build;

import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunCoordinator;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 执行器领取：并发上限、锁语义、租约续期与执行线程调度。 */
class GraphBuildExecutorTest {

    private static GraphBuildRunRecord run(long id, long kbId, long fencingToken) {
        return new GraphBuildRunRecord(id, 9L, kbId, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.QUEUED, GraphBuildStage.QUEUED, fencingToken,
                null, null, 0L, "kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
    }

    private GraphBuildRepository repository;
    private GraphBuildRunCoordinator coordinator;
    private GraphBuildRunner runner;
    private KwikiDistributedLocks locks;
    private GraphBuildExecutor executor;

    @BeforeEach
    void setUp() {
        repository = mock(GraphBuildRepository.class);
        coordinator = mock(GraphBuildRunCoordinator.class);
        runner = mock(GraphBuildRunner.class);
        locks = mock(KwikiDistributedLocks.class);
        when(locks.isAvailable()).thenReturn(true);
        // 租约 3s → 每 1s 续租，测试可观察
        executor = new GraphBuildExecutor(repository, coordinator, runner, locks,
                new GraphBuildProperties(Duration.ofMillis(50), 2, Duration.ofSeconds(3),
                        1, Duration.ZERO, 100, 50));
    }

    @Test
    void 存活子任务达到全局上限时不领取() {
        when(repository.countLiveRuns(any())).thenReturn(2);

        executor.poll();

        verify(repository, never()).findClaimableRuns(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 分布式锁被其他实例占用时跳过本轮() {
        when(locks.acquire(anyString(), any(), any())).thenReturn(null);

        executor.poll();

        verify(repository, never()).countLiveRuns(any());
    }

    @Test
    void 锁服务不可用时视为单实例继续领取() {
        when(locks.acquire(anyString(), any(), any())).thenReturn(null);
        when(locks.isAvailable()).thenReturn(false);
        when(repository.countLiveRuns(any())).thenReturn(0);
        when(repository.findClaimableRuns(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        executor.poll();

        verify(repository).countLiveRuns(any());
    }

    @Test
    void 领取后经租约与隔离令牌交给执行线程() {
        when(locks.acquire(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        when(repository.countLiveRuns(any())).thenReturn(0);
        GraphBuildRunRecord candidate = run(12L, 7L, 3L);
        when(repository.findClaimableRuns(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(candidate));
        when(coordinator.recover(eq(7L), anyString(), eq(Duration.ofSeconds(3)))).thenReturn(4L);
        when(repository.findActiveRunForUpdate(7L))
                .thenReturn(Optional.of(run(12L, 7L, 4L)));

        executor.poll();

        verify(runner, timeout(2000)).run(eq(run(12L, 7L, 4L)), anyString(), eq(4L), any());
        verify(repository, never()).failRun(anyLong(), anyString(), anyLong(), anyString(),
                anyString());
    }

    @Test
    void 租约被其他实例接管时跳过候选() {
        when(locks.acquire(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        when(repository.countLiveRuns(any())).thenReturn(0);
        when(repository.findClaimableRuns(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(run(12L, 7L, 3L)));
        when(coordinator.recover(eq(7L), anyString(), eq(Duration.ofSeconds(3))))
                .thenThrow(new IllegalStateException("图构建租约已被其他 worker 接管"));

        executor.poll();

        verify(runner, never()).run(any(), anyString(), anyLong(), any());
    }

    @Test
    void 续租定时任务按租约三分之一周期续期() throws Exception {
        when(locks.acquire(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        when(repository.countLiveRuns(any())).thenReturn(0);
        when(repository.findClaimableRuns(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(run(12L, 7L, 3L)));
        when(coordinator.recover(eq(7L), anyString(), eq(Duration.ofSeconds(3)))).thenReturn(4L);
        when(repository.findActiveRunForUpdate(7L))
                .thenReturn(Optional.of(run(12L, 7L, 4L)));
        when(repository.renewLease(eq(12L), anyString(), eq(4L), eq(Duration.ofSeconds(3))))
                .thenReturn(true);
        // 让执行线程阻塞，等待续租定时任务触发
        org.mockito.Mockito.doAnswer(invocation -> {
            Thread.sleep(2500);
            return null;
        }).when(runner).run(any(), anyString(), anyLong(), any());

        executor.poll();
        // 租约 3s → 约 1s 后首次续租
        verify(repository, timeout(4000).atLeastOnce())
                .renewLease(eq(12L), anyString(), eq(4L), eq(Duration.ofSeconds(3)));
    }

    @Test
    void 停止后不再领取() {
        executor.shutdown();
        when(locks.acquire(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));

        executor.poll();

        verify(repository, never()).countLiveRuns(any());
    }
}
