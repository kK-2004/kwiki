package com.kwiki.rag.orchestration;

import com.kwiki.wiki.access.AuthorizationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RunContextObserverTest {

    RunContext run() {
        return new RunContext("req-1", new AuthorizationScope(1L, true, Set.of(), Map.of()),
                kb -> 0L, AgenticLimits.defaults(), Map.of());
    }

    @Test
    void noopObserverNeverBuildsEventPayload() {
        var built = new AtomicInteger();
        run().observe(() -> {
            built.incrementAndGet();
            return new RunObserver.RewriteObserved(1, "q");
        });
        assertThat(built).hasValue(0);
    }

    @Test
    void installedObserverReceivesEvents() {
        var run = run();
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();
        run.observer(events::add);
        run.observe(() -> new RunObserver.ContextObserved(0, "BASE_CHILD", "q", "上下文"));
        assertThat(events).containsExactly(new RunObserver.ContextObserved(0, "BASE_CHILD", "q", "上下文"));
    }

    @Test
    void failingObserverDoesNotPropagate() {
        var run = run();
        run.observer(event -> { throw new IllegalStateException("boom"); });
        assertThatCode(() -> run.observe(() -> new RunObserver.RewriteObserved(1, "q")))
                .doesNotThrowAnyException();
    }

    @Test
    void nullObserverFallsBackToNoop() {
        var run = run();
        run.observer(null);
        var built = new AtomicInteger();
        run.observe(() -> { built.incrementAndGet(); return new RunObserver.RewriteObserved(1, "q"); });
        assertThat(built).hasValue(0);
    }
}
