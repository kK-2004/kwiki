package com.kwiki.usage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageRepositoryTest {
    final JdbcOperations jdbc = mock(JdbcOperations.class);

    @SuppressWarnings("unchecked")
    ModelUsageRepository repository() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new ModelUsageRepository(provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertAccumulatesOnDuplicateKeyWithUtcBucket() {
        var aggregate = new MinuteAggregate(Instant.parse("2026-10-21T01:02:00Z"), UsageSource.EVAL, "ANSWER",
                "dashscope", "qwen-plus", 3, 2, 1, 150, 25, 400);

        repository().upsertMinutes(List.of(aggregate));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("INSERT INTO model_usage_minute")
                .contains("ON DUPLICATE KEY UPDATE").contains("calls = calls + VALUES(calls)")
                .contains("input_tokens = input_tokens + VALUES(input_tokens)");
        Object[] row = args.getValue().getFirst();
        assertThat(row[0]).isEqualTo(LocalDateTime.parse("2026-10-21T01:02:00"));
        assertThat(row).containsSequence("EVAL", "ANSWER", "dashscope", "qwen-plus", 3L, 2L, 1L, 150L, 25L, 400L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void callsAreInsertedWithStatus() {
        var event = new UsageEvent(Instant.parse("2026-10-21T01:02:03.456Z"), UsageSource.ONLINE, "REWRITE",
                "p", "m", 9, "req-1", 10, 2, 33, false, "AuthenticationException");

        repository().insertCalls(List.of(event));

        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), args.capture());
        Object[] row = args.getValue().getFirst();
        assertThat(row[0]).isEqualTo(LocalDateTime.parse("2026-10-21T01:02:03.456"));
        assertThat(row).contains("FAILED", "AuthenticationException", "req-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void longRequestIdIsTruncatedToColumnWidth() {
        var event = new UsageEvent(Instant.parse("2026-10-21T01:02:03.456Z"), UsageSource.ONLINE, "ANSWER",
                "p", "m", 9, "r".repeat(120), 10, 2, 33, true, null);

        repository().insertCalls(List.of(event));

        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), args.capture());
        Object[] row = args.getValue().getFirst();
        assertThat((String) row[6]).hasSize(64);
    }

    @Test
    void deletesRunInBatchesUntilNothingIsLeft() {
        when(jdbc.update(anyString(), eq(LocalDateTime.parse("2026-04-24T00:00:00"))))
                .thenReturn(ModelUsageRepository.DELETE_BATCH, 123, 0);

        int deleted = repository().deleteMinutesBefore(Instant.parse("2026-04-24T00:00:00Z"));

        assertThat(deleted).isEqualTo(ModelUsageRepository.DELETE_BATCH + 123);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingDatabaseIsANoOp() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        var repository = new ModelUsageRepository(provider);
        assertThat(repository.available()).isFalse();
        repository.upsertMinutes(List.of());
        assertThat(repository.deleteCallsBefore(Instant.now())).isZero();
    }
}
