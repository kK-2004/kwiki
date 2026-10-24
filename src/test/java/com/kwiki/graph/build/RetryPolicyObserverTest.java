package com.kwiki.graph.build;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 重试观察者：每次失败尝试回调一次，并说明之后是否还会重试。 */
class RetryPolicyObserverTest {

    private final List<String> seen = new ArrayList<>();
    private final RetryPolicy.AttemptObserver observer =
            (failure, willRetry) -> seen.add(failure.getMessage() + "/" + willRetry);

    @Test
    void retryableFailuresReportWhetherAnotherAttemptFollows() {
        AtomicInteger calls = new AtomicInteger();

        String result = RetryPolicy.of(3, Duration.ZERO).run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw GraphBuildFailure.retryable("MODEL_TIMEOUT", "t" + calls.get());
            }
            return "ok";
        }, observer);

        assertThat(result).isEqualTo("ok");
        assertThat(seen).containsExactly("t1/true", "t2/true");
    }

    @Test
    void exhaustedAttemptsReportTheLastOneAsFinal() {
        assertThatThrownBy(() -> RetryPolicy.of(2, Duration.ZERO).run(() -> {
            throw new IllegalStateException("io");
        }, observer)).hasMessage("io");

        assertThat(seen).containsExactly("io/true", "io/false");
    }

    @Test
    void permanentFailureIsReportedOnceWithoutRetry() {
        assertThatThrownBy(() -> RetryPolicy.of(5, Duration.ZERO).run(() -> {
            throw GraphBuildFailure.permanent("EXTRACTION_INVALID_JSON", "bad");
        }, observer)).isInstanceOf(GraphBuildFailure.class);

        assertThat(seen).containsExactly("bad/false");
    }

    @Test
    void cancellationIsNotAFailedAttempt() {
        assertThatThrownBy(() -> RetryPolicy.of(5, Duration.ZERO).run(() -> {
            throw new CancellationException("stop");
        }, observer)).isInstanceOf(CancellationException.class);

        assertThat(seen).isEmpty();
    }

    @Test
    void runContextCountsOnlyModelTimeoutsAsTimeouts() {
        List<String> stats = new ArrayList<>();
        var run = new com.kwiki.graph.persistence.GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, com.kwiki.graph.persistence.GraphBuildState.EXTRACTING,
                com.kwiki.graph.persistence.GraphBuildStage.EXTRACTING, 3L,
                "owner-a", null, 0L, "kwiki-extractor-1", "arcadedb-leiden-1", "kwiki-summarizer-1");
        RunContext ctx = new RunContext(run, "owner-a", 3L, () -> false, progress -> {
        }, (timeout, willRetry) -> stats.add(timeout + "/" + willRetry));

        ctx.modelAttempts().attemptFailed(GraphBuildFailure.retryable(GraphBuildFailure.MODEL_TIMEOUT, "t"), true);
        ctx.modelAttempts().attemptFailed(GraphBuildFailure.retryable("MODEL_UNAVAILABLE", "5xx"), true);
        ctx.modelAttempts().attemptFailed(new IllegalStateException("io"), false);

        assertThat(stats).containsExactly("true/true", "false/true", "false/false");
    }
}
