package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 基于租约、作用于 {@code indexing_job_target} 的批量认领：每个物理
 * 目标独立执行、独立退避。可领取的是 PENDING/RETRY_WAIT 中退避已到期的
 * 目标行，以及 LEASED 中租约已过期的行。一次带守卫的 UPDATE 最多租出
 * 批量大小的目标，因此并发认领者绝不会同时持有同一份有效租约。
 *
 * <p>遗留迁移：升级前已排队、还没有目标行的任务，在认领前为其回填
 * 当前 selected 版本的目标行——已 COMPLETED 的旧任务永不重开，
 * 因此不会重复已完成的 v1 工作。</p>
 */
@Repository
public class IndexingJobTargetClaimer {

    private final JdbcOperations jdbc;
    private final int leaseSeconds;

    public IndexingJobTargetClaimer(ObjectProvider<JdbcOperations> jdbc,
                                    @Value("${kwiki.indexing.lease-seconds:300}") int leaseSeconds) {
        this.jdbc = jdbc.getIfAvailable();
        this.leaseSeconds = leaseSeconds;
    }

    /** 为 {@code owner} 认领最多 {@code max} 个目标行；返回目标 + 任务联查行。 */
    @Transactional
    public List<Map<String, Object>> claim(String owner, int max) {
        if (jdbc == null || max <= 0) {
            return List.of();
        }
        backfillLegacyJobs();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(leaseSeconds);

        jdbc.update("""
                        UPDATE indexing_job_target
                        SET state = 'LEASED', lease_owner = ?, lease_expires_at = ?,
                            attempts = attempts + 1
                        WHERE id IN (
                            SELECT id FROM (
                                SELECT t.id FROM indexing_job_target t
                                WHERE (t.state IN ('PENDING', 'RETRY_WAIT')
                                       AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= ?))
                                   OR (t.state = 'LEASED' AND t.lease_expires_at <= ?)
                                ORDER BY t.id
                                LIMIT ?
                            ) eligible)
                        """,
                owner, expiresAt, now, now, max);

        return jdbc.queryForList("""
                        SELECT t.id AS target_id, t.job_id, t.event_id, t.target_version,
                               t.physical_name, t.state AS target_state, t.attempts,
                               j.job_type, j.resource_type, j.resource_id, j.revision_id,
                               j.expected_lifecycle_version
                FROM indexing_job_target t
                JOIN indexing_job j ON j.id = t.job_id
                WHERE t.lease_owner = ? AND t.lease_expires_at = ?
                ORDER BY t.id
                """, owner, expiresAt);
    }

    /**
     * 升级兼容：没有目标行的遗留任务（升级前入队、尚未终结）回填
     * selected 版本目标；无 selected 版本时回填最大未删除版本。
     * 只针对非终结任务，已完成的 v1 工作不会被重开。
     */
    private void backfillLegacyJobs() {
        jdbc.update("""
                        INSERT INTO indexing_job_target
                            (job_id, event_id, target_version, physical_name,
                             idempotency_key, state, attempts, max_attempts)
                        SELECT j.id, NULL, v.version_number, v.physical_name,
                               CONCAT(j.id, ':v', v.version_number), 'PENDING', 0, 8
                        FROM indexing_job j
                        JOIN (
                            SELECT COALESCE(
                                (SELECT version_number FROM search_index_version
                                 WHERE selected = TRUE AND deleted_at IS NULL
                                 ORDER BY version_number DESC LIMIT 1),
                                (SELECT MAX(version_number) FROM search_index_version
                                 WHERE deleted_at IS NULL)) AS version_number
                        ) chosen ON chosen.version_number IS NOT NULL
                        JOIN search_index_version v ON v.version_number = chosen.version_number
                        WHERE j.state IN ('PENDING', 'RETRY_WAIT', 'LEASED')
                          AND NOT EXISTS (SELECT 1 FROM indexing_job_target t WHERE t.job_id = j.id)
                        """);
    }
}
