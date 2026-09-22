package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GraphOfflineBudgetTest {

    @Test
    void retriesWithinTheOfflineAttemptBudgetAndWarnsAfterSixHours() {
        GraphOfflineModelLimiter limiter = new GraphOfflineModelLimiter(2, Duration.ZERO, 3);
        AtomicInteger attempts = new AtomicInteger();
        String value = limiter.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("retry");
            return "ok";
        });
        assertThat(value).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(GraphBatchAgePolicy.evaluate(Instant.EPOCH,
                Instant.EPOCH.plus(Duration.ofHours(6).plusSeconds(1))).warning()).isTrue();
    }
}
