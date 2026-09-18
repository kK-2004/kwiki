package com.kwiki.wiki.archive;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 每日 01:00（Asia/Shanghai）触发回收站物理清理。专用 SDK 分布式锁
 * （kwiki:lock:recycle-bin-cleanup）防止多个实例处理同一批过期批次；
 * 无法获取锁的实例直接跳过本次触发——次日运行（或本实例的下次触发）
 * 会接管该工作。锁服务不可用时同样跳过本轮（失败关闭）。
 */
@Component
@EnableScheduling
public class RecycleBinCleanupScheduler {

    public static final String LOCK_PURPOSE = "recycle-bin-cleanup";

    private static final Logger log = LoggerFactory.getLogger(RecycleBinCleanupScheduler.class);

    private final RecycleBinCleanupService cleanup;
    private final KwikiDistributedLocks locks;

    public RecycleBinCleanupScheduler(RecycleBinCleanupService cleanup, KwikiDistributedLocks locks) {
        this.cleanup = cleanup;
        this.locks = locks;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Shanghai")
    public void daily() {
        String jobId = cleanup.newJobId();
        boolean ran = locks.tryRun(LOCK_PURPOSE, Duration.ZERO, () -> {
            try {
                cleanup.runOnce(jobId);
            } catch (Exception e) {
                log.error("recycle-bin cleanup {} aborted: {}", jobId, e.getMessage(), e);
            }
        });
        if (!ran) {
            log.info("recycle-bin cleanup {} skipped: lock unavailable or held by another instance", jobId);
        }
    }
}
