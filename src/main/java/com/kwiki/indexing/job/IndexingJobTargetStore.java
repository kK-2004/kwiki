package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 目标级生命周期记账：每个物理版本的独立完成、有界退避失败与
 * 管理员重试；任务行的状态只是全部目标行终结后的聚合视图。
 * 影子目标的失败不会伪装成当前目标的失败，也不会被当前目标的
 * 成功掩盖——它们保留自己的错误与重试状态。错误以净化后的形式
 * 存储（类名 + 有界摘要）。
 */
@Repository
public class IndexingJobTargetStore {

    private final JdbcOperations jdbc;

    public IndexingJobTargetStore(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** 目标成功终结；任务聚合在全部目标终结后推进。 */
    public void completeTarget(long targetId) {
        if (jdbc == null) {
            return;
        }
        jdbc.update("UPDATE indexing_job_target SET state = 'COMPLETED', lease_owner = NULL, "
                + "lease_expires_at = NULL, next_attempt_at = NULL, "
                + "lock_version = lock_version + 1 WHERE id = ? AND state = 'LEASED'", targetId);
        aggregateJob(jobIdOf(targetId));
    }

    /** 目标级有界指数退避：base * 2^attempts，上限为 maxBackoffSeconds。 */
    public void failTarget(long targetId, String errorClass, String sanitizedSummary,
                           int maxAttempts, long baseBackoffSeconds, long maxBackoffSeconds) {
        if (jdbc == null) {
            return;
        }
        jdbc.update("""
                        UPDATE indexing_job_target
                        SET state = IF(attempts >= ?, 'FAILED', 'RETRY_WAIT'),
                            last_error_class = ?, last_error_summary = ?,
                            lease_owner = NULL, lease_expires_at = NULL,
                            next_attempt_at = IF(attempts >= ?, NULL,
                                CURRENT_TIMESTAMP(6) + INTERVAL LEAST(?, ?) SECOND),
                            lock_version = lock_version + 1
                        WHERE id = ? AND state = 'LEASED'
                        """,
                maxAttempts, errorClass, sanitizedSummary, maxAttempts,
                baseBackoffSeconds, maxBackoffSeconds, targetId);
        aggregateJob(jobIdOf(targetId));
    }

    /** 管理员重试：重新打开已终结的目标（连带任务）。 */
    public boolean adminRetryTarget(long targetId) {
        if (jdbc == null) {
            return false;
        }
        boolean reopened = jdbc.update(
                "UPDATE indexing_job_target SET state = 'PENDING', attempts = 0, "
                        + "next_attempt_at = NULL, last_error_class = NULL, "
                        + "last_error_summary = NULL, lease_owner = NULL, "
                        + "lease_expires_at = NULL, lock_version = lock_version + 1 "
                        + "WHERE id = ? AND state = 'FAILED'", targetId) > 0;
        if (reopened) {
            jdbc.update("UPDATE indexing_job SET state = 'PENDING' WHERE id = ? "
                    + "AND state = 'FAILED'", jobIdOf(targetId));
        }
        return reopened;
    }

    /** 供管理端按版本统计多写健康度。 */
    public List<Map<String, Object>> targetStatsByVersion() {
        if (jdbc == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                        SELECT target_version,
                               COUNT(*) AS total,
                               SUM(state = 'COMPLETED') AS succeeded,
                               SUM(state IN ('PENDING', 'LEASED')) AS pending,
                               SUM(state = 'RETRY_WAIT') AS retrying,
                               SUM(state = 'FAILED') AS failed,
                               TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(6)) AS elapsed_seconds,
                               TIMESTAMPDIFF(SECOND,
                                   MIN(CASE WHEN state IN ('PENDING','LEASED','RETRY_WAIT') THEN created_at END),
                                   CURRENT_TIMESTAMP(6)) AS lag_seconds,
                               ROUND(SUM(state = 'COMPLETED') /
                                   GREATEST(TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(6)), 1), 3)
                                   AS throughput_per_second,
                               CASE WHEN SUM(state = 'COMPLETED') = 0 THEN NULL ELSE ROUND(
                                   SUM(state IN ('PENDING','LEASED','RETRY_WAIT')) /
                                   (SUM(state = 'COMPLETED') /
                                    GREATEST(TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(6)), 1)))
                                   END AS eta_seconds,
                               MAX(updated_at) AS last_transition_at
                FROM indexing_job_target
                GROUP BY target_version
                ORDER BY target_version
                """);
    }

    /**
     * 任务聚合：仍有未终结目标时保持运行视图；全部终结后，
     * 任一目标成功即 COMPLETED（影子失败可见于目标行与版本统计），
     * 否则 FAILED。
     */
    private void aggregateJob(Long jobId) {
        if (jobId == null) {
            return;
        }
        jdbc.update("""
                        UPDATE indexing_job j
                        SET j.state = IF(
                                EXISTS (SELECT 1 FROM indexing_job_target t
                                        WHERE t.job_id = j.id
                                          AND t.state IN ('PENDING', 'RETRY_WAIT', 'LEASED')),
                                j.state,
                                IF(EXISTS (SELECT 1 FROM indexing_job_target t
                                           WHERE t.job_id = j.id AND t.state = 'COMPLETED'),
                                   'COMPLETED', 'FAILED')),
                            j.lease_owner = NULL,
                            j.lease_expires_at = NULL,
                            j.lock_version = j.lock_version + 1
                        WHERE j.id = ?
                          AND NOT EXISTS (SELECT 1 FROM indexing_job_target t
                                          WHERE t.job_id = j.id
                                            AND t.state IN ('PENDING', 'RETRY_WAIT', 'LEASED'))
                        """, jobId);
    }

    private Long jobIdOf(long targetId) {
        if (jdbc == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT job_id FROM indexing_job_target WHERE id = ?", Long.class, targetId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
