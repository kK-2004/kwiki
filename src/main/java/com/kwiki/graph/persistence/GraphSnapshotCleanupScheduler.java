package com.kwiki.graph.persistence;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 退役快照的自动清理：每 5 分钟在分布式锁 {@code graph:snapshot-cleanup} 下扫描 DELETING
 * 快照，宽限期已过且没有未到期读取租约的，删除其 graphVersion 的 ArcadeDB 数据与社区
 * ES 索引（与批次删除同一套清理）。未到宽限期的跳过，失败的保留资源行下一轮重试。
 * 管理端「清理」按钮仍可立即触发单个快照。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphSnapshotCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(GraphSnapshotCleanupScheduler.class);
    private static final String LOCK = "graph:snapshot-cleanup";

    private final GraphBuildRepository repository;
    private final GraphSnapshotRetirementService retirement;
    private final GraphArtifactCleaner artifacts;
    private final KwikiDistributedLocks locks;

    public GraphSnapshotCleanupScheduler(GraphBuildRepository repository,
                                         GraphSnapshotRetirementService retirement,
                                         GraphArtifactCleaner artifacts,
                                         KwikiDistributedLocks locks) {
        this.repository = repository;
        this.retirement = retirement;
        this.artifacts = artifacts;
        this.locks = locks;
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void tick() {
        runOnce();
    }

    /** 一轮清理；返回资源已全部删除的快照数。锁不可用时跳过（不无锁执行）。 */
    public int runOnce() {
        if (!locks.isAvailable()) {
            return 0;
        }
        Integer completed = locks.tryRunReturning(LOCK, Duration.ofSeconds(10), () -> {
            int count = 0;
            for (Long snapshotId : repository.findSnapshotsAwaitingCleanup()) {
                try {
                    var result = retirement.cleanupResources(snapshotId, artifacts);
                    if (result.complete()) {
                        count++;
                        log.info("retired graph snapshot {} cleaned: {} resources deleted",
                                snapshotId, result.deleted());
                    } else if (result.deleted() > 0 || result.remaining() > 0) {
                        log.debug("retired graph snapshot {} cleanup pending: deleted={} remaining={}",
                                snapshotId, result.deleted(), result.remaining());
                    }
                } catch (RuntimeException failure) {
                    log.warn("retired graph snapshot {} cleanup failed: {}", snapshotId, failure.getMessage());
                }
            }
            return count;
        });
        return completed == null ? 0 : completed;
    }
}
