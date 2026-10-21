package com.kwiki.usage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 用量表的 JDBC 访问；未装配数据源时所有操作为 no-op。时间一律按 UTC 绑定。 */
@Repository
public class ModelUsageRepository {

    static final int DELETE_BATCH = 10_000;

    private static final String UPSERT_MINUTE = """
            INSERT INTO model_usage_minute
              (bucket_start, source, step, provider, model, user_id,
               calls, failures, input_tokens, output_tokens, latency_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              calls = calls + VALUES(calls),
              failures = failures + VALUES(failures),
              input_tokens = input_tokens + VALUES(input_tokens),
              output_tokens = output_tokens + VALUES(output_tokens),
              latency_ms = latency_ms + VALUES(latency_ms)
            """;

    private static final String INSERT_CALL = """
            INSERT INTO model_usage_call
              (occurred_at, source, step, provider, model, user_id, request_id,
               input_tokens, output_tokens, latency_ms, status, error_type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcOperations jdbc;

    public ModelUsageRepository(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public boolean available() {
        return jdbc != null;
    }

    public void upsertMinutes(List<MinuteAggregate> aggregates) {
        if (jdbc == null || aggregates.isEmpty()) return;
        jdbc.batchUpdate(UPSERT_MINUTE, aggregates.stream().map(a -> new Object[]{
                utc(a.bucketStart()), a.source().name(), a.step(), a.provider(), a.model(), a.userId(),
                a.calls(), a.failures(), a.inputTokens(), a.outputTokens(), a.latencyMs()}).toList());
    }

    public void insertCalls(List<UsageEvent> events) {
        if (jdbc == null || events.isEmpty()) return;
        jdbc.batchUpdate(INSERT_CALL, events.stream().map(e -> new Object[]{
                utc(e.occurredAt()), e.source().name(), e.step(), e.provider(), e.model(), e.userId(),
                truncate(e.requestId(), 64), e.inputTokens(), e.outputTokens(),
                (int) Math.min(e.latencyMs(), Integer.MAX_VALUE),
                e.success() ? "SUCCESS" : "FAILED", truncate(e.errorType(), 128)}).toList());
    }

    public int deleteMinutesBefore(Instant cutoff) {
        return deleteInBatches("DELETE FROM model_usage_minute WHERE bucket_start < ? LIMIT " + DELETE_BATCH, cutoff);
    }

    public int deleteCallsBefore(Instant cutoff) {
        return deleteInBatches("DELETE FROM model_usage_call WHERE occurred_at < ? LIMIT " + DELETE_BATCH, cutoff);
    }

    private int deleteInBatches(String sql, Instant cutoff) {
        if (jdbc == null) return 0;
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, utc(cutoff));
            total += deleted;
        } while (deleted >= DELETE_BATCH);
        return total;
    }

    public record SeriesRow(long bucketEpochSeconds, String step, String provider, String model,
                            long calls, long failures, long inputTokens, long outputTokens, long latencyMs) {
    }

    public record UserRow(long userId, String displayName, long totalTokens) {
    }

    /** 按（桶、步骤、provider、模型）汇总；桶起点 = floor((秒数 + offset) / size) * size - offset。 */
    public List<SeriesRow> querySeries(Instant from, Instant to, Set<UsageSource> sources, Set<Long> users,
                                       long bucketSeconds, long offsetSeconds) {
        if (jdbc == null) return List.of();
        List<Object> args = new ArrayList<>(List.of(offsetSeconds, bucketSeconds, bucketSeconds, offsetSeconds,
                utc(from), utc(to)));
        StringBuilder sql = new StringBuilder("""
                SELECT FLOOR((TIMESTAMPDIFF(SECOND, '1970-01-01 00:00:00', bucket_start) + ?) / ?) * ? - ?
                         AS bucket_epoch,
                       step, provider, model,
                       SUM(calls) AS calls, SUM(failures) AS failures,
                       SUM(input_tokens) AS input_tokens, SUM(output_tokens) AS output_tokens,
                       SUM(latency_ms) AS latency_ms
                FROM model_usage_minute
                WHERE bucket_start >= ? AND bucket_start < ?
                """);
        appendFilters(sql, args, sources, users);
        sql.append(" GROUP BY bucket_epoch, step, provider, model");
        return jdbc.query(sql.toString(), (rs, i) -> new SeriesRow(rs.getLong("bucket_epoch"),
                rs.getString("step"), rs.getString("provider"), rs.getString("model"), rs.getLong("calls"),
                rs.getLong("failures"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getLong("latency_ms")), args.toArray());
    }

    public List<UserRow> queryUsers(Instant from, Instant to, Set<UsageSource> sources) {
        if (jdbc == null) return List.of();
        List<Object> args = new ArrayList<>(List.of(utc(from), utc(to)));
        StringBuilder sql = new StringBuilder("""
                SELECT u.user_id, a.display_name, SUM(u.input_tokens + u.output_tokens) AS total_tokens
                FROM model_usage_minute u
                LEFT JOIN app_user a ON a.id = u.user_id
                WHERE u.bucket_start >= ? AND u.bucket_start < ?
                """);
        appendFilters(sql, args, sources, Set.of());
        sql.append(" GROUP BY u.user_id, a.display_name ORDER BY total_tokens DESC");
        return jdbc.query(sql.toString(), (rs, i) -> new UserRow(rs.getLong("user_id"),
                rs.getString("display_name"), rs.getLong("total_tokens")), args.toArray());
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, Set<UsageSource> sources,
                                      Set<Long> users) {
        if (!sources.isEmpty()) {
            sql.append(" AND source IN (").append(String.join(", ", Collections.nCopies(sources.size(), "?")))
                    .append(")");
            sources.forEach(source -> args.add(source.name()));
        }
        if (!users.isEmpty()) {
            sql.append(" AND user_id IN (").append(String.join(", ", Collections.nCopies(users.size(), "?")))
                    .append(")");
            args.addAll(users);
        }
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
