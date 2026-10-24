package com.kwiki.graph.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 待重建记录（graph_rebuild_request，每库一行）的仓储。
 * 标记在业务写入事务内 upsert（保留 first_dirty_at，硬删除只置位）；
 * 调度器查询可提交的库、记录提交批次与结果、按指数退避重试；
 * 发布快照的 epoch 覆盖 dirty epoch 后清除。一行的读取与更新都很短，
 * 与 graph_source_epoch 一样依赖行级互斥而非应用内锁。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphRebuildRequestRepository {

    /** 一行待重建记录的只读视图。 */
    public record GraphRebuildRequestRecord(long kbId, Instant firstDirtyAt, Instant lastDirtyAt,
                                            long dirtyContentEpoch, long dirtySecurityEpoch,
                                            boolean hardDelete, Long lastSubmittedBatchId,
                                            String lastOutcome, int attemptCount,
                                            Instant nextAttemptAt) {
    }

    private final JdbcOperations jdbc;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public GraphRebuildRequestRepository(ObjectProvider<JdbcOperations> jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    GraphRebuildRequestRepository(ObjectProvider<JdbcOperations> jdbc, Clock clock) {
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.clock = clock;
    }

    /**
     * 标记该库待重建；必须与 epoch 推进在同一业务事务中调用。
     * 新建写入 first_dirty_at；之后只刷新 last_dirty_at 与 dirty epoch；
     * hard_delete 一旦置位不再清除（行在发布后被整体删除）。
     * dirty epoch 相比存量前进视为新一轮变化：重置退避与尝试次数。
     * MySQL 按从左到右赋值，后面的表达式读到的是已更新的列值，所以两个 IF
     * 必须排在 dirty epoch 赋值之前，才能和旧 epoch 比较。
     */
    public void markDirty(long kbId, long contentEpoch, long securityEpoch, boolean hardDelete) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO graph_rebuild_request
                    (kb_id, first_dirty_at, last_dirty_at, dirty_content_epoch,
                     dirty_security_epoch, hard_delete)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    attempt_count = IF(VALUES(dirty_content_epoch) > dirty_content_epoch
                                        OR VALUES(dirty_security_epoch) > dirty_security_epoch,
                                       0, attempt_count),
                    next_attempt_at = IF(VALUES(dirty_content_epoch) > dirty_content_epoch
                                          OR VALUES(dirty_security_epoch) > dirty_security_epoch,
                                         NULL, next_attempt_at),
                    last_dirty_at = VALUES(last_dirty_at),
                    dirty_content_epoch = VALUES(dirty_content_epoch),
                    dirty_security_epoch = VALUES(dirty_security_epoch),
                    hard_delete = hard_delete OR VALUES(hard_delete)
                """, kbId, Timestamp.from(now), Timestamp.from(now), contentEpoch, securityEpoch,
                hardDelete);
    }

    /** 全部待重建记录，按 kbId 确定顺序。 */
    public List<GraphRebuildRequestRecord> findAll() {
        return jdbc.query("SELECT kb_id, first_dirty_at, last_dirty_at, dirty_content_epoch, "
                        + "dirty_security_epoch, hard_delete, last_submitted_batch_id, last_outcome, "
                        + "attempt_count, next_attempt_at FROM graph_rebuild_request ORDER BY kb_id",
                (rs, row) -> new GraphRebuildRequestRecord(
                        rs.getLong(1),
                        rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant(),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getBoolean(6),
                        rs.getObject(7) == null ? null : rs.getLong(7),
                        rs.getString(8),
                        rs.getInt(9),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()));
    }

    /** 提交成功：记录批次、递增尝试次数并清空退避时间。 */
    public void recordSubmitted(long kbId, long batchId) {
        jdbc.update("UPDATE graph_rebuild_request SET last_submitted_batch_id = ?, "
                + "last_outcome = 'SUBMITTED', attempt_count = attempt_count + 1, next_attempt_at = NULL "
                + "WHERE kb_id = ?", batchId, kbId);
    }

    /** 终态结局：STALE/FAILED/CANCELLED 保留记录，按退避时间安排重试。 */
    public void recordOutcome(long kbId, String outcome, Instant nextAttemptAt) {
        jdbc.update("UPDATE graph_rebuild_request SET last_outcome = ?, next_attempt_at = ?, "
                + "last_submitted_batch_id = NULL WHERE kb_id = ?",
                outcome, Timestamp.from(nextAttemptAt), kbId);
    }

    /** 发布快照 epoch 已覆盖 dirty epoch 后清除该行。 */
    public void clear(long kbId) {
        jdbc.update("DELETE FROM graph_rebuild_request WHERE kb_id = ?", kbId);
    }

    /**
     * 该库在指定 Chunk 版本下活动快照自身捕获的 epoch；未发布过返回 null。
     * 不读发布行上的 epoch：回滚会把当前 epoch 写进发布行，而快照内容是旧的，
     * 用发布行判断会误以为已覆盖 dirty epoch 并清除记录。
     */
    public long[] publishedEpochs(long kbId, int chunkIndexVersion) {
        return jdbc.query("SELECT s.content_epoch, s.security_epoch FROM graph_publication p "
                        + "JOIN graph_snapshot s ON s.id = p.active_snapshot_id "
                        + "WHERE p.kb_id = ? AND p.chunk_index_version = ?",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null,
                kbId, chunkIndexVersion);
    }

    /** 该库是否有活动中的图构建子任务（QUEUED 或任一执行态）。 */
    public boolean hasActiveBuild(long kbId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM graph_build_run "
                + "WHERE kb_id = ? AND state IN ('QUEUED', 'EXTRACTING', 'PROJECTING', 'CLUSTERING', "
                + "'SUMMARIZING', 'INDEXING', 'VALIDATING', 'WAITING_FOR_CHUNKS', 'UNSUPPORTED', "
                + "'NEEDS_ATTENTION')", Integer.class, kbId);
        return count != null && count > 0;
    }

    /** 已提交批次中该库子任务的状态与提交时捕获的 epoch。 */
    public record SubmittedRun(String state, long contentEpoch, long securityEpoch) {
    }

    /** 指定批次中该库的子任务；不存在（如批次已被清理）返回 null。 */
    public SubmittedRun submittedRun(long batchId, long kbId) {
        return jdbc.query("SELECT state, content_epoch, security_epoch FROM graph_build_run "
                        + "WHERE batch_id = ? AND kb_id = ?",
                rs -> rs.next() ? new SubmittedRun(rs.getString(1), rs.getLong(2), rs.getLong(3)) : null,
                batchId, kbId);
    }

    /**
     * 已提交批次结束、但发布结果没有覆盖当前 dirty epoch（如发布后又有编辑、
     * 候选已过期或子任务记录缺失）：解除对该批次的等待，按普通待重建记录继续调度。
     * 不计为失败，不设置退避。
     */
    public void releaseSubmission(long kbId) {
        jdbc.update("UPDATE graph_rebuild_request SET last_submitted_batch_id = NULL, "
                + "next_attempt_at = NULL WHERE kb_id = ?", kbId);
    }

    /** 知识库是否仍为 ACTIVE；已归档或已物理删除的库不再自动重建。 */
    public boolean knowledgeBaseActive(long kbId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE id = ? AND status = 'ACTIVE'",
                Integer.class, kbId);
        return count != null && count > 0;
    }

    /**
     * 该库是否有未完成的索引任务（PENDING/LEASED/RETRY_WAIT）：
     * 保证 ES 已反映最新内容后再构建，避免基于旧 Chunk 构建后又立即 STALE。
     */
    public boolean hasPendingIndexingJobs(long kbId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM indexing_job j
                WHERE j.state IN ('PENDING', 'LEASED', 'RETRY_WAIT')
                  AND (
                    (j.resource_type = 'PAGE' AND j.resource_id IN
                        (SELECT id FROM wiki_page WHERE kb_id = ?))
                 OR (j.resource_type = 'ATTACHMENT' AND j.resource_id IN
                        (SELECT id FROM attachment WHERE kb_id = ?))
                 OR (j.resource_type = 'KNOWLEDGE_BASE' AND j.resource_id = ?)
                  )
                """, Integer.class, kbId, kbId, kbId);
        return count != null && count > 0;
    }
}
