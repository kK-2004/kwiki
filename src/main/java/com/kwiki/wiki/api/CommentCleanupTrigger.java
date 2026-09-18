package com.kwiki.wiki.api;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.EmptyResultDataAccessException;

import java.time.Duration;

/** 默认触发器；XXL-JOB 适配器日后可以调用同一套策略。 */
@Component
@EnableScheduling
public class CommentCleanupTrigger {
    private static final String LOCK_PURPOSE = "comment-cleanup";

    private final CommentCleanupStrategy strategy;
    private final org.springframework.jdbc.core.JdbcOperations jdbc;
    private final MeterRegistry metrics;
    private final KwikiDistributedLocks locks;

    public CommentCleanupTrigger(CommentCleanupStrategy strategy,
                                 ObjectProvider<org.springframework.jdbc.core.JdbcOperations> jdbc,
                                 ObjectProvider<MeterRegistry> metrics,
                                 KwikiDistributedLocks locks) {
        this.strategy = strategy;
        this.jdbc = jdbc.getIfAvailable();
        this.metrics = metrics.getIfAvailable();
        this.locks = locks;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Shanghai")
    public void daily() {
        if (jdbc == null) return;
        // 锁不可得（他实例持有或锁服务不可用）时跳过本轮；游标保证下次从断点继续。
        locks.tryRun(LOCK_PURPOSE, Duration.ZERO, () -> {
            try {
                long cursor = loadCursor();
                for (int i = 0; i < 100; i++) {
                    CommentCleanupStrategy.CleanupBatch batch = strategy.cleanupBatch(500, cursor);
                    if (metrics != null && batch.processed() > 0) metrics.counter("kwiki_comment_cleanup_rows_total").increment(batch.processed());
                    if (batch.processed() == 0 || batch.nextCursor() <= cursor) break;
                    cursor = batch.nextCursor();
                    saveCursor(cursor);
                }
            } catch (RuntimeException failure) {
                if (metrics != null) metrics.counter("kwiki_comment_cleanup_failures_total").increment();
                throw failure;
            }
        });
    }

    private long loadCursor() {
        try {
            Long value = jdbc.queryForObject("SELECT cursor_id FROM comment_cleanup_cursor WHERE job_name = 'orphaned-replies'", Long.class);
            return value == null ? 0L : value;
        } catch (EmptyResultDataAccessException missing) {
            jdbc.update("INSERT INTO comment_cleanup_cursor (job_name, cursor_id) VALUES ('orphaned-replies', 0) ON DUPLICATE KEY UPDATE job_name = job_name");
            return 0L;
        }
    }

    private void saveCursor(long cursor) {
        jdbc.update("INSERT INTO comment_cleanup_cursor (job_name, cursor_id) VALUES ('orphaned-replies', ?) ON DUPLICATE KEY UPDATE cursor_id = GREATEST(cursor_id, VALUES(cursor_id))", cursor);
    }
}
