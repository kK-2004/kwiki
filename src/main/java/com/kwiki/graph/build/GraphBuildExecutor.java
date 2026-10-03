package com.kwiki.graph.build;

import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunCoordinator;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图构建执行器：按全局并发上限领取子任务，在虚拟线程中推进全部阶段。
 * 分布式锁保证同一时刻只有一个实例领取；锁服务不可用时视为单实例继续执行。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildExecutor {

    private final GraphBuildRepository repository;
    private final GraphBuildRunCoordinator coordinator;
    private final GraphBuildRunner runner;
    private final KwikiDistributedLocks locks;
    private final GraphBuildProperties buildProperties;
    private final String instanceOwner = "graph-exec-"
            + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService runThreads =
            Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Autowired
    public GraphBuildExecutor(GraphBuildRepository repository,
                              GraphBuildRunCoordinator coordinator,
                              GraphBuildRunner runner,
                              KwikiDistributedLocks locks,
                              GraphBuildProperties buildProperties) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.runner = runner;
        this.locks = locks;
        this.buildProperties = buildProperties;
    }

    @Scheduled(fixedDelayString = "${kwiki.graph.build.poll-interval:10s}")
    public void poll() {
        if (shuttingDown.get()) {
            return;
        }
        // 分布式锁 200ms 快速等待、60s 有界租期；未获取（其他实例在领取）跳过本轮，
        // 锁服务不可用时视为单实例继续执行
        AutoCloseable lock = locks.acquire("graph-build-executor",
                Duration.ofMillis(200), Duration.ofSeconds(60));
        if (lock == null && locks.isAvailable()) {
            return;
        }
        try {
            claimAndLaunch();
        } catch (RuntimeException failure) {
            // 单轮领取失败不影响下一轮调度
        } finally {
            closeQuietly(lock);
        }
    }

    private void claimAndLaunch() {
        var now = java.time.Instant.now();
        int live = repository.countLiveRuns(now);
        int capacity = buildProperties.maxConcurrentRuns() - live;
        if (capacity <= 0) {
            return;
        }
        List<GraphBuildRunRecord> candidates = repository.findClaimableRuns(now, capacity);
        for (GraphBuildRunRecord candidate : candidates) {
            if (shuttingDown.get()) {
                return;
            }
            long fencingToken;
            try {
                fencingToken = coordinator.recover(candidate.kbId(), instanceOwner,
                        buildProperties.leaseDuration());
            } catch (IllegalStateException busy) {
                // 租约被其他实例接管或状态不允许：跳过该候选
                continue;
            }
            // 租约获取成功后重读子任务，携带新隔离令牌交给执行线程
            GraphBuildRunRecord leased = repository.findActiveRunForUpdate(candidate.kbId())
                    .orElse(null);
            if (leased == null || leased.fencingToken() != fencingToken) {
                continue;
            }
            launch(leased, fencingToken);
        }
    }

    private void launch(GraphBuildRunRecord run, long fencingToken) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().factory());
        long renewEveryMs = Math.max(1000L,
                buildProperties.leaseDuration().toMillis() / 3);
        renewer.scheduleAtFixedRate(() -> {
            if (!repository.renewLease(run.id(), instanceOwner, fencingToken,
                    buildProperties.leaseDuration())) {
                leaseLost.set(true);
            }
        }, renewEveryMs, renewEveryMs, TimeUnit.MILLISECONDS);
        runThreads.submit(() -> {
            try {
                runner.run(run, instanceOwner, fencingToken, leaseLost::get);
            } catch (RuntimeException failure) {
                // 执行线程的未预期异常不影响执行器本身
            } finally {
                renewer.shutdownNow();
            }
        });
    }

    /** 停止续租与执行线程；不修改任何子任务状态，交由租约过期后接管。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        shuttingDown.set(true);
        runThreads.shutdownNow();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 解锁失败交由租约到期
        }
    }
}
