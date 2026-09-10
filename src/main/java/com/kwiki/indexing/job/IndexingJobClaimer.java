package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 基于租约、作用于 indexing_job 表的批量认领。可领取的任务是
 * PENDING/RETRY_WAIT 中退避已到期的记录，以及 LEASED 中租约
 * 已过期的记录（确定性的重新收回）。一次带守卫的 UPDATE 最多租出批量
 * 大小的任务，因此两个并发的认领者绝不会同时持有同一份有效租约。
 */
@Repository
public class IndexingJobClaimer {

    private final JdbcOperations jdbc;
    private final int defaultLeaseSeconds;

    public IndexingJobClaimer(ObjectProvider<JdbcOperations> jdbc,
                              @Value("${kwiki.indexing.lease-seconds:300}") int defaultLeaseSeconds) {
        this.jdbc = jdbc.getIfAvailable();
        this.defaultLeaseSeconds = defaultLeaseSeconds;
    }

    /** 为 {@code owner} 认领最多 {@code max} 个任务；返回已租出的任务 id。 */
    @Transactional
    public List<Long> claim(String owner, int max) {
        if (jdbc == null) {
            return List.of();
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(defaultLeaseSeconds);

        jdbc.update("""
                        UPDATE indexing_job
                        SET state = 'LEASED', lease_owner = ?, lease_expires_at = ?,
                            attempts = attempts + 1
                        WHERE id IN (
                            SELECT id FROM (
                                SELECT id FROM indexing_job
                                WHERE (state IN ('PENDING', 'RETRY_WAIT')
                                       AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                                   OR (state = 'LEASED' AND lease_expires_at <= ?)
                                ORDER BY id
                                LIMIT ?
                            ) eligible)
                        """,
                owner, expiresAt, now, now, max);

        return jdbc.queryForList(
                "SELECT id FROM indexing_job WHERE lease_owner = ? AND lease_expires_at = ? ORDER BY id",
                Long.class, owner, expiresAt);
    }
}
