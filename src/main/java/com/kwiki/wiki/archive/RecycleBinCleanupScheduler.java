package com.kwiki.wiki.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/**
 * Daily 01:00 Asia/Shanghai trigger for the recycle-bin physical cleanup. A
 * dedicated MySQL advisory lock (acquired and released on one connection)
 * keeps multiple instances from processing the same expired batches; an
 * instance that cannot take the lock simply skips the tick — the next day's
 * run (or this instance's next tick) picks the work up.
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
