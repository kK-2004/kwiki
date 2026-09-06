package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Lease-based batch claiming over the indexing_job table. Eligible jobs are
 * PENDING/RETRY_WAIT rows whose backoff elapsed plus LEASED rows whose lease
 * expired (deterministic reclaim). A single guarded UPDATE leases up to the batch
 * size, so two concurrent claimers can never own the same live lease.
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

    /** Claims up to {@code max} jobs for {@code owner}; returns the leased job ids. */
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
