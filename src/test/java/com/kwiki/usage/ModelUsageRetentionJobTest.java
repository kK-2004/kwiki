package com.kwiki.usage;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelUsageRetentionJobTest {

    @Test
    void deletesOlderThanConfiguredRetention() {
        var repository = mock(ModelUsageRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        var job = new ModelUsageRetentionJob(repository, mock(KwikiDistributedLocks.class), 180, 7, clock);

        job.runOnce();

        verify(repository).deleteMinutesBefore(Instant.parse("2026-04-24T00:00:00Z"));
        verify(repository).deleteCallsBefore(Instant.parse("2026-10-14T00:00:00Z"));
    }
}
