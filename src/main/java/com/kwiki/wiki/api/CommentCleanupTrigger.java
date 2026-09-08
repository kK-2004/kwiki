package com.kwiki.wiki.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.EmptyResultDataAccessException;

/** Default trigger; an XXL-JOB adapter can call the same strategy later. */
@Component
@EnableScheduling
public class CommentCleanupTrigger {
    private final CommentCleanupStrategy strategy;
    private final org.springframework.jdbc.core.JdbcOperations jdbc;
    private final MeterRegistry metrics;

    public CommentCleanupTrigger(CommentCleanupStrategy strategy, ObjectProvider<org.springframework.jdbc.core.JdbcOperations> jdbc, ObjectProvider<MeterRegistry> metrics) {
        this.strategy = strategy;
        this.jdbc = jdbc.getIfAvailable();
        this.metrics = metrics.getIfAvailable();
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Shanghai")
    public void daily() {
        if (jdbc == null) return;
        Boolean acquired = jdbc.queryForObject("SELECT GET_LOCK('kwiki:comment-cleanup', 0)", Boolean.class);
        if (!Boolean.TRUE.equals(acquired)) return;
        try {
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
        } finally {
            jdbc.queryForObject("SELECT RELEASE_LOCK('kwiki:comment-cleanup')", Integer.class);
        }
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
