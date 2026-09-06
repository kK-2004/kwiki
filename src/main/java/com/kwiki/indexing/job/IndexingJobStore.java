package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC operations for job lifecycle bookkeeping: completion, bounded-backoff
 * failure, administrator retry, queue metrics, and detail/list reads for the
 * admin API. Errors are stored sanitized (class + bounded summary).
 */
@Repository
public class IndexingJobStore {

    private final JdbcOperations jdbc;

    public IndexingJobStore(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public void complete(long jobId) {
        if (jdbc == null) {
            return;
        }
        jdbc.update("UPDATE indexing_job SET state = 'COMPLETED', lease_owner = NULL, "
                + "lease_expires_at = NULL, next_attempt_at = NULL, lock_version = lock_version + 1 "
                + "WHERE id = ? AND state = 'LEASED'", jobId);
    }

    /** Bounded exponential backoff: base * 2^attempts capped at maxBackoffSeconds. */
    public void fail(long jobId, String errorClass, String sanitizedSummary, int maxAttempts,
                     long baseBackoffSeconds, long maxBackoffSeconds) {
        if (jdbc == null) {
            return;
        }
        jdbc.update("""
                        UPDATE indexing_job
                        SET state = IF(attempts >= ?, 'FAILED', 'RETRY_WAIT'),
                            last_error_class = ?, last_error_summary = ?,
                            lease_owner = NULL, lease_expires_at = NULL,
                            next_attempt_at = IF(attempts >= ?, NULL,
                                CURRENT_TIMESTAMP(6) + INTERVAL LEAST(?, ?) SECOND),
                            lock_version = lock_version + 1
                        WHERE id = ? AND state = 'LEASED'
                        """,
                maxAttempts, errorClass, sanitizedSummary, maxAttempts,
                baseBackoffSeconds, maxBackoffSeconds, jobId);
    }

    /** Administrator retry: re-opens a terminal job from PENDING with a clean slate. */
    public boolean adminRetry(long jobId) {
        if (jdbc == null) {
            return false;
        }
        return jdbc.update("UPDATE indexing_job SET state = 'PENDING', attempts = 0, "
                + "next_attempt_at = NULL, last_error_class = NULL, last_error_summary = NULL, "
                + "lease_owner = NULL, lease_expires_at = NULL, lock_version = lock_version + 1 "
                + "WHERE id = ? AND state = 'FAILED'", jobId) > 0;
    }

    public long queueDepth() {
        if (jdbc == null) {
            return 0;
        }
        Long depth = jdbc.queryForObject(
                "SELECT COUNT(*) FROM indexing_job WHERE state IN ('PENDING', 'RETRY_WAIT', 'LEASED')",
                Long.class);
        return depth == null ? 0 : depth;
    }

    public Optional<Map<String, Object>> findById(long jobId) {
        if (jdbc == null) {
            return Optional.empty();
        }
        return jdbc.queryForList(
                "SELECT id, job_type, resource_type, resource_id, revision_id, state, attempts, "
                        + "max_attempts, next_attempt_at, last_error_class, last_error_summary, "
                        + "created_at, updated_at FROM indexing_job WHERE id = ?", jobId)
                .stream().findFirst();
    }

    public List<Map<String, Object>> list(String state, int limit) {
        if (jdbc == null) {
            return List.of();
        }
        if (state == null || state.isBlank()) {
            return jdbc.queryForList(
                    "SELECT id, job_type, resource_type, resource_id, state, attempts, "
                            + "max_attempts, next_attempt_at, last_error_class, created_at, updated_at "
                            + "FROM indexing_job ORDER BY id DESC LIMIT ?", limit);
        }
        return jdbc.queryForList(
                "SELECT id, job_type, resource_type, resource_id, state, attempts, "
                        + "max_attempts, next_attempt_at, last_error_class, created_at, updated_at "
                        + "FROM indexing_job WHERE state = ? ORDER BY id DESC LIMIT ?", state, limit);
    }
}
