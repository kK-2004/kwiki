package com.kwiki.usage;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageRecorderTest {

    static UsageEvent event(int input) {
        return new UsageEvent(Instant.parse("2026-10-21T01:02:03Z"), UsageSource.ONLINE, "ANSWER", "p", "m",
                1, "r", input, 1, 10, true, null);
    }

    @Test
    void recordsAndDrainsInOrder() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        recorder.record(event(1));
        recorder.record(event(2));
        assertThat(recorder.drain()).extracting(UsageEvent::inputTokens).containsExactly(1, 2);
        assertThat(recorder.drain()).isEmpty();
    }

    @Test
    void fullQueueDropsInsteadOfBlocking() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        for (int i = 0; i < ModelUsageRecorder.CAPACITY + 10; i++) recorder.record(event(1));
        assertThat(recorder.drain()).hasSize(ModelUsageRecorder.CAPACITY);
        assertThat(recorder.dropped()).isEqualTo(10);
    }

    @Test
    void nullEventsAreIgnored() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        recorder.record(null);
        assertThat(recorder.drain()).isEmpty();
    }
}
