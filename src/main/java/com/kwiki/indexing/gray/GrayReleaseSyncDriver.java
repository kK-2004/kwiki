package com.kwiki.indexing.gray;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 定时推进同步中的灰度：确认双写 → 存量迁移 → 校验。单个灰度失败只记录日志。 */
@Component
public class GrayReleaseSyncDriver {

    private static final Logger log = LoggerFactory.getLogger(GrayReleaseSyncDriver.class);
    private static final String LOCK_PURPOSE = "gray-release-sync";
    private static final Duration LOCK_WAIT = Duration.ofMillis(200);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(60);

    private final GrayReleaseStore store;
    private final GrayReleaseService service;
    private final KwikiDistributedLocks locks;

    public GrayReleaseSyncDriver(GrayReleaseStore store, GrayReleaseService service) {
        this(store, service, null);
    }

    @Autowired
    public GrayReleaseSyncDriver(GrayReleaseStore store, GrayReleaseService service,
                                 KwikiDistributedLocks locks) {
        this.store = store;
        this.service = service;
        this.locks = locks;
    }

    /**
     * 多实例部署时各实例都会触发本任务；若两个实例同时看到本会话尚无迁移并各自发起，
     * 会重复提交迁移，因此整轮推进必须在分布式锁下串行。
     * 未抢到锁说明其它实例正在推进，直接跳过本轮。
     * 锁服务不可用（SDK 关闭或未装配）时 acquire 恒返回 null，此时视为单实例部署，
     * 无锁推进，否则灰度将永远无法自动完成同步。
     */
    @Scheduled(fixedDelayString = "${kwiki.indexing.gray.sync-interval:10s}")
    public void tick() {
        if (locks == null || !locks.isAvailable()) {
            advanceAll();
            return;
        }
        AutoCloseable held = locks.acquire(LOCK_PURPOSE, LOCK_WAIT, LOCK_LEASE);
        if (held == null) {
            return;
        }
        try (held) {
            advanceAll();
        } catch (Exception failure) {
            log.warn("gray release sync tick failed: {}: {}",
                    failure.getClass().getSimpleName(), failure.getMessage());
        }
    }

    private void advanceAll() {
        for (GrayRelease release : store.findByStatus(GrayReleaseStatus.SYNCING)) {
            try {
                service.advance(release.id());
            } catch (RuntimeException failure) {
                log.warn("gray release {} advance failed: {}: {}", release.id(),
                        failure.getClass().getSimpleName(), failure.getMessage());
            }
        }
    }
}
