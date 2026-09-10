package com.kwiki.wiki.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/**
 * 每日 01:00（Asia/Shanghai）触发回收站物理清理。一把专用 MySQL 咨询锁
 * （在同一连接上获取与释放）防止多个实例处理同一批过期批次；无法获取
 * 锁的实例直接跳过本次触发——次日运行（或本实例的下次触发）会接管该工作。
 */
@Component
@EnableScheduling
public class RecycleBinCleanupScheduler {

    public static final String LOCK_NAME = "kwiki:recycle-bin-cleanup";

    private static final Logger log = LoggerFactory.getLogger(RecycleBinCleanupScheduler.class);

    private final RecycleBinCleanupService cleanup;
    private final MysqlAdvisoryLocks locks;

    public RecycleBinCleanupScheduler(RecycleBinCleanupService cleanup, MysqlAdvisoryLocks locks) {
        this.cleanup = cleanup;
        this.locks = locks;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Shanghai")
    public void daily() {
        String jobId = cleanup.newJobId();
        boolean ran = locks.tryWithLock(LOCK_NAME, () -> {
            try {
                cleanup.runOnce(jobId);
            } catch (Exception e) {
                log.error("recycle-bin cleanup {} aborted: {}", jobId, e.getMessage(), e);
            }
        });
        if (!ran) {
            log.info("recycle-bin cleanup {} skipped: lock held by another instance", jobId);
        }
    }
}
