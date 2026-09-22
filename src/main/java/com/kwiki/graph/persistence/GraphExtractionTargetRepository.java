package com.kwiki.graph.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.kwiki.infrastructure.observability.SecretRedaction;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 图写入和 ES 回填目标分别租约、重试和完成，单目标失败不回滚另一目标。 */
@Repository
@ConditionalOnBean(JdbcTemplate.class)
public class GraphExtractionTargetRepository {

    private final JdbcTemplate jdbc;

    public GraphExtractionTargetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<GraphExtractionTargetRecord> claim(String targetKind, String owner,
                                                       Duration leaseDuration) {
        Optional<GraphExtractionTargetRecord> candidate = jdbc.query(
                "SELECT id, extraction_id, target_kind, target_identity, attempts, max_attempts, "
                        + "lease_owner, lease_expires_at FROM graph_extraction_target "
                        + "WHERE target_kind = ? AND state IN ('PENDING', 'RETRY_WAIT') "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(6)) "
                        + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                rs -> rs.next() ? Optional.of(new GraphExtractionTargetRecord(rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                        rs.getString(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()))
                        : Optional.empty(), targetKind);
        if (candidate.isEmpty()) return Optional.empty();
        GraphExtractionTargetRecord target = candidate.get();
        int updated = jdbc.update("UPDATE graph_extraction_target SET state = 'LEASED', attempts = attempts + 1, "
                        + "lease_owner = ?, lease_expires_at = ?, lock_version = lock_version + 1 "
                        + "WHERE id = ? AND state IN ('PENDING', 'RETRY_WAIT')",
                owner, Timestamp.from(Instant.now().plus(leaseDuration)), target.id());
        return updated == 1 ? Optional.of(target) : Optional.empty();
    }

    public boolean complete(long targetId, String owner) {
        return jdbc.update("UPDATE graph_extraction_target SET state = 'COMPLETED', lease_owner = NULL, "
                        + "lease_expires_at = NULL, next_attempt_at = NULL, lock_version = lock_version + 1 "
                        + "WHERE id = ? AND state = 'LEASED' AND lease_owner = ?", targetId, owner) == 1;
    }

    public boolean fail(long targetId, String owner, String errorClass, String summary,
                        Duration backoff) {
        return jdbc.update("UPDATE graph_extraction_target SET state = IF(attempts >= max_attempts, 'FAILED', 'RETRY_WAIT'), "
                        + "last_error_class = ?, last_error_summary = ?, lease_owner = NULL, lease_expires_at = NULL, "
                        + "next_attempt_at = IF(attempts >= max_attempts, NULL, CURRENT_TIMESTAMP(6) + INTERVAL ? SECOND), "
                        + "lock_version = lock_version + 1 WHERE id = ? AND state = 'LEASED' AND lease_owner = ?",
                errorClass, bounded(summary), Math.max(1, backoff.toSeconds()), targetId, owner) == 1;
    }

    private static String bounded(String summary) {
        if (summary == null) return null;
        String redacted = SecretRedaction.redact(summary);
        return redacted.length() <= 1000 ? redacted : redacted.substring(0, 1000);
    }
}
