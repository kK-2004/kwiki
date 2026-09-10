package com.kwiki.infrastructure.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;

/** 在 Redis 写入结果不确定后从 MySQL 重建计数器；绝不重放增量。 */
@Configuration
@ConditionalOnProperty(name = "kwiki.stats.repair.enabled", havingValue = "true")
public class WikiStatisticsRepairScheduler {
    private final JdbcOperations jdbc;
    private final WikiStatisticsCache cache;
    private final int batchSize;

    public WikiStatisticsRepairScheduler(JdbcOperations jdbc, WikiStatisticsCache cache,
                                         @Value("${kwiki.stats.repair.batch-size:50}") int batchSize) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(fixedDelayString = "${kwiki.stats.repair.interval:60000}")
    public void repair() {
        jdbc.query("SELECT id, page_id FROM stats_repair WHERE completed_at IS NULL ORDER BY id LIMIT ?",
                (rs, row) -> new long[] { rs.getLong(1), rs.getLong(2) }, batchSize)
                .forEach(item -> {
                    long pageId = item[1];
                    WikiStatisticsCache.Counters counters = new WikiStatisticsCache.Counters(
                            count("SELECT COUNT(*) FROM page_like WHERE page_id = ?", pageId),
                            count("SELECT COUNT(*) FROM page_favorite WHERE page_id = ?", pageId),
                            count("SELECT COUNT(*) FROM wiki_comment c LEFT JOIN wiki_comment root ON root.id = c.parent_id "
                                    + "WHERE c.page_id = ? AND c.deleted_at IS NULL "
                                    + "AND (c.parent_id IS NULL OR root.deleted_at IS NULL)", pageId),
                            version(pageId));
                    cache.replace(pageId, counters);
                    jdbc.update("UPDATE stats_repair SET completed_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND completed_at IS NULL", item[0]);
                });
    }

    private long count(String sql, long pageId) {
        Long value = jdbc.queryForObject(sql, Long.class, pageId);
        return value == null ? 0L : value;
    }

    private long version(long pageId) {
        Long value = jdbc.queryForObject("SELECT version FROM stats_revision WHERE page_id = ?", Long.class, pageId);
        return value == null ? 1L : value;
    }
}
