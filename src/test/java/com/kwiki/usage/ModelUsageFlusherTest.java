package com.kwiki.usage;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageFlusherTest {

    static UsageEvent event(String at, String step, long user, int in, int out, long latency, boolean ok) {
        return new UsageEvent(Instant.parse(at), UsageSource.ONLINE, step, "dashscope", "qwen-plus", user, "r",
                in, out, latency, ok, ok ? null : "HttpException");
    }

    @Test
    void sameMinuteAndCombinationAreSummed() {
        var aggregates = ModelUsageFlusher.aggregate(List.of(
                event("2026-10-21T01:02:03Z", "ANSWER", 1, 100, 20, 300, true),
                event("2026-10-21T01:02:59.900Z", "ANSWER", 1, 50, 5, 100, false),
                event("2026-10-21T01:03:00Z", "ANSWER", 1, 7, 0, 1, true),
                event("2026-10-21T01:02:10Z", "REWRITE", 1, 9, 9, 9, true),
                event("2026-10-21T01:02:10Z", "ANSWER", 2, 1, 1, 1, true)));

        assertThat(aggregates).hasSize(4);
        MinuteAggregate first = aggregates.stream()
                .filter(a -> a.step().equals("ANSWER") && a.userId() == 1
                        && a.bucketStart().equals(Instant.parse("2026-10-21T01:02:00Z")))
                .findFirst().orElseThrow();
        assertThat(first.calls()).isEqualTo(2);
        assertThat(first.failures()).isEqualTo(1);
        assertThat(first.inputTokens()).isEqualTo(150);
        assertThat(first.outputTokens()).isEqualTo(25);
        assertThat(first.latencyMs()).isEqualTo(400);
    }

    @Test
    void flushWritesAggregatesAndCalls() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        var repository = mock(ModelUsageRepository.class);
        when(repository.available()).thenReturn(true);
        recorder.record(event("2026-10-21T01:02:03Z", "ANSWER", 1, 100, 20, 300, true));

        new ModelUsageFlusher(recorder, repository).flush();

        verify(repository).upsertMinutes(anyList());
        verify(repository).insertCalls(anyList());
        assertThat(recorder.drain()).isEmpty();
    }

    @Test
    void emptyQueueOrMissingDatabaseWritesNothing() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        var repository = mock(ModelUsageRepository.class);
        when(repository.available()).thenReturn(false);
        recorder.record(event("2026-10-21T01:02:03Z", "ANSWER", 1, 1, 1, 1, true));

        new ModelUsageFlusher(recorder, repository).flush();

        verify(repository, never()).upsertMinutes(any());
        assertThat(recorder.drain()).isEmpty(); // 无数据库时丢弃，避免无限堆积
    }

    @Test
    void databaseFailureIsSwallowed() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        var repository = mock(ModelUsageRepository.class);
        when(repository.available()).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(repository).upsertMinutes(anyList());
        recorder.record(event("2026-10-21T01:02:03Z", "ANSWER", 1, 1, 1, 1, true));

        new ModelUsageFlusher(recorder, repository).flush();

        verify(repository).upsertMinutes(anyList());
    }
}
