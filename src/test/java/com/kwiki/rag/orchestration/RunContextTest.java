package com.kwiki.rag.orchestration;

import static org.assertj.core.api.Assertions.*;

import com.kwiki.wiki.access.AuthorizationScope;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

class RunContextTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    RunContext run(Clock clock, AtomicLong version) {
        return new RunContext(
                "test",
                new AuthorizationScope(1, false, Set.of(2L), Map.of(2L, 1L)),
                id -> version.get(),
                AgenticLimits.defaults(),
                Map.of(),
                clock);
    }

    @Test
    void nodeAndGlobalDeadlinesUseClock() throws Exception {
        var clock = new MutableClock();
        var run = run(clock, new AtomicLong(1));
        try (var node = run.limit(Duration.ofSeconds(10))) {
            clock.now = clock.now.plusSeconds(10);
            assertThatThrownBy(run::remaining).hasMessage("timeout");
        }
        assertThat(run.remaining()).isEqualTo(Duration.ofSeconds(170));
        clock.now = clock.now.plusSeconds(170);
        assertThatThrownBy(run::check).hasMessage("timeout");
    }

    @Test
    void budgetsAndAuthorizationAreIndependentPerRequest() {
        var version = new AtomicLong(1);
        var first = run(new MutableClock(), version);
        var second = run(new MutableClock(), version);
        for (int i = 0; i < 16; i++) first.modelCall();
        assertThat(first.canModel()).isFalse();
        assertThat(second.canModel()).isTrue();
        assertThatThrownBy(first::modelCall).hasMessage("model-budget-exhausted");
        for (int i = 0; i < 9; i++) first.toolCall();
        assertThatThrownBy(first::toolCall).hasMessage("tool-budget-exhausted");
        version.incrementAndGet();
        assertThatThrownBy(second::authorize).hasMessage("authorization-changed");
    }

    @Test
    void cancellationClosesRegisteredWorkAndBindingRestores() throws Exception {
        var first = run(new MutableClock(), new AtomicLong(1));
        var second = run(new MutableClock(), new AtomicLong(1));
        var calls = new AtomicInteger();
        first.onCancel(calls::incrementAndGet);
        try (var binding = first.bind()) {
            try (var nested = second.bind()) {
                assertThat(RunContext.current()).isSameAs(second);
            }
            assertThat(RunContext.current()).isSameAs(first);
        }
        assertThat(RunContext.current()).isNull();
        first.close();
        first.close();
        assertThat(calls).hasValue(1);
        first.onCancel(calls::incrementAndGet);
        assertThat(calls).hasValue(2);
        assertThatThrownBy(first::check).hasMessage("cancelled");
        second.check();
    }
}
