package com.kwiki.wiki.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 幂等且有界的 MySQL 清理，日后可由 XXL-JOB 调用。 */
@Service
public class JdbcCommentCleanupStrategy implements CommentCleanupStrategy {
    private final JdbcOperations jdbc;

    public JdbcCommentCleanupStrategy(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @Override
    @Transactional
    public int cleanupBatch(int batchSize) {
        if (jdbc == null) return 0;
        int limit = Math.max(1, Math.min(batchSize, 1000));
        return jdbc.update("UPDATE wiki_comment child JOIN wiki_comment root ON root.id = child.parent_id "
                + "SET child.deleted_at = COALESCE(child.deleted_at, CURRENT_TIMESTAMP(6)) "
                + "WHERE root.deleted_at IS NOT NULL AND child.deleted_at IS NULL LIMIT " + limit);
    }

    @Override
    @Transactional
    public CleanupBatch cleanupBatch(int batchSize, long afterId) {
        if (jdbc == null) return new CleanupBatch(0, afterId);
        int limit = Math.max(1, Math.min(batchSize, 1000));
        java.util.List<Long> ids = jdbc.query("SELECT child.id FROM wiki_comment child JOIN wiki_comment root ON root.id = child.parent_id "
                + "WHERE child.id > ? AND root.deleted_at IS NOT NULL AND child.deleted_at IS NULL ORDER BY child.id LIMIT " + limit,
                (rs, row) -> rs.getLong(1), afterId);
        if (ids.isEmpty()) return new CleanupBatch(0, afterId);
        String placeholders = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        int processed = jdbc.update("UPDATE wiki_comment child JOIN wiki_comment root ON root.id = child.parent_id "
                + "SET child.deleted_at = COALESCE(child.deleted_at, CURRENT_TIMESTAMP(6)) "
                + "WHERE child.id IN (" + placeholders + ") AND root.deleted_at IS NOT NULL AND child.deleted_at IS NULL", ids.toArray());
        return new CleanupBatch(processed, ids.get(ids.size() - 1));
    }
}
