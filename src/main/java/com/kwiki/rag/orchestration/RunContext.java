package com.kwiki.rag.orchestration;

import com.kwiki.wiki.access.AuthorizationScope;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongUnaryOperator;

/** Trusted request context. Bind only around application-owned work, never deserialize it. */
public final class RunContext implements AutoCloseable {
    private static final ThreadLocal<RunContext> CURRENT = new ThreadLocal<>();
    public final String requestId;
    public final AuthorizationScope scope;
    public final AgenticLimits limits;
    public final Map<String, String> traceHeaders;
    private final LongUnaryOperator versions;
    private final Clock clock;
    private final Instant deadline;
    private Instant nodeDeadline;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Runnable> cancellations = ConcurrentHashMap.newKeySet();
    private final AtomicInteger models = new AtomicInteger(),
            tools = new AtomicInteger(),
            steps = new AtomicInteger();

    public RunContext(
            String id,
            AuthorizationScope scope,
            LongUnaryOperator versions,
            AgenticLimits limits,
            Map<String, String> traceHeaders) {
        this(id, scope, versions, limits, traceHeaders, Clock.systemUTC());
    }

    public RunContext(
            String id,
            AuthorizationScope scope,
            LongUnaryOperator versions,
            AgenticLimits limits,
            Map<String, String> traceHeaders,
            Clock clock) {
        this.requestId = Objects.requireNonNull(id);
        this.scope = Objects.requireNonNull(scope);
        this.versions = versions;
        this.limits = limits;
        this.traceHeaders = Map.copyOf(traceHeaders);
        this.clock = clock;
        this.deadline = clock.instant().plus(limits.timeout());
    }

    public static RunContext current() {
        return CURRENT.get();
    }

    public AutoCloseable bind() {
        var before = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        };
    }

    public void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new RunFailure("cancelled");
        if (!clock.instant().isBefore(deadline)) throw new RunFailure("timeout");
    }

    public void authorize() {
        check();
        if (scope.isStale(versions)) throw new RunFailure("authorization-changed");
    }

    public AutoCloseable limit(Duration duration) {
        Instant previous = nodeDeadline;
        nodeDeadline = clock.instant().plus(duration);
        return () -> nodeDeadline = previous;
    }

    public Duration remaining() {
        check();
        Instant end =
                nodeDeadline != null && nodeDeadline.isBefore(deadline) ? nodeDeadline : deadline;
        if (!clock.instant().isBefore(end)) throw new RunFailure("timeout");
        return Duration.between(clock.instant(), end);
    }

    public Duration timeout(Duration limit) {
        Duration left = remaining();
        return left.compareTo(limit) < 0 ? left : limit;
    }

    public void modelCall() {
        check();
        if (models.incrementAndGet() > limits.modelCalls())
            throw new RunFailure("model-budget-exhausted");
    }

    public void toolCall() {
        authorize();
        if (tools.incrementAndGet() > limits.toolCalls())
            throw new RunFailure("tool-budget-exhausted");
    }

    public void step() {
        authorize();
        if (steps.incrementAndGet() > limits.steps()) throw new RunFailure("execution-limit");
    }

    public boolean canModel() {
        return models.get() < limits.modelCalls();
    }

    public boolean canTools(int count) {
        return tools.get() + count <= limits.toolCalls();
    }

    public Map<String, Object> stats() {
        return Map.of("modelCalls", models.get(), "toolCalls", tools.get(), "steps", steps.get());
    }

    public AutoCloseable onCancel(Runnable action) {
        cancellations.add(action);
        if (cancelled.get() && cancellations.remove(action)) action.run();
        return () -> cancellations.remove(action);
    }

    public void close() {
        if (cancelled.compareAndSet(false, true)) {
            for (var action : cancellations) {
                try {
                    action.run();
                } catch (Exception ignored) {
                }
            }
            cancellations.clear();
        }
    }
}
