package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RebuildTargetEnqueuerTest {

    @Test
    void archiveReplayBecomesTargetSpecificDeleteWithOriginalEventId() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForObject(contains("idempotency_key"), eq(Long.class),
                org.mockito.ArgumentMatchers.any())).thenReturn(77L);
        RebuildTargetEnqueuer enqueuer = new RebuildTargetEnqueuer(jdbc);

        enqueuer.replayEvent(9, 2, "kwiki-chunks-v2",
                new RebuildTargetEnqueuer.ChangeEvent(
                        41, "PAGE", 7, 103L, "ARCHIVE", 4));

        verify(jdbc).update(contains("INTO indexing_job\n"),
                eq("DELETE"), eq("PAGE"), eq(7L), eq(103L), eq(4L),
                contains("CATCHUP:9:EVENT:41"));
        verify(jdbc).update(contains("INTO indexing_job_target"),
                eq(77L), eq(41L), eq(2), eq("kwiki-chunks-v2"),
                contains(":v2"));
    }

    @Test
    void restoreReplayBecomesIdempotentUpsert() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForObject(contains("idempotency_key"), eq(Long.class),
                org.mockito.ArgumentMatchers.any())).thenReturn(78L);
        RebuildTargetEnqueuer enqueuer = new RebuildTargetEnqueuer(jdbc);

        enqueuer.replayEvent(9, 2, "kwiki-chunks-v2",
                new RebuildTargetEnqueuer.ChangeEvent(
                        42, "PAGE", 7, 104L, "RESTORE", 5));

        verify(jdbc).update(contains("ON DUPLICATE KEY UPDATE"),
                eq("UPSERT"), eq("PAGE"), eq(7L), eq(104L), eq(5L),
                contains("CATCHUP:9:EVENT:42"));
    }
}
