package com.kwiki.usage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

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

    protected final JdbcOperations jdbc;

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
                e.requestId(), e.inputTokens(), e.outputTokens(), (int) Math.min(e.latencyMs(), Integer.MAX_VALUE),
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

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
