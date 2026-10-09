package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwiki.graph.GraphSelectionScope;
import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.orchestration.RunFailure;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.HybridRetrievalOrchestrator;
import com.kwiki.wiki.access.AuthorizationScope;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class AgentToolDispatcherTest {
    final ToolRegistry registry = new ToolRegistry();
    final AtomicLong version = new AtomicLong(1);
    final RunContext run = run(AgenticLimits.defaults());

    RunContext run(AgenticLimits limits) {
        return new RunContext("r", new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L)),
                id -> version.get(), limits, Map.of());
    }

    ToolExecutionContext context(RunContext run) {
        return new ToolExecutionContext(run, new EvidencePool("q"), "q", List.of(), GraphSelectionScope.all(), 1);
    }

    /** 可编程的假执行器。 */
    static class Fake implements AgentToolExecutor {
        int calls;
        Supplier<Outcome> behavior = () -> new Outcome(List.of("C1"), List.of(), Map.of("addedCount", 1));

        @Override
        public String name() {
            return AgentTools.SEARCH_CHUNKS;
        }

        @Override
        public Outcome execute(AgentToolArguments arguments, ToolExecutionContext context) {
            calls++;
            return behavior.get();
        }
    }

    ToolRegistry.ValidatedAgentCall call(String id, String query) {
        return registry.validateAgentBatch(List.of(new ToolCall(id, "search_chunks",
                "{\"queries\":[\"" + query + "\"],\"strategy\":\"BM25\",\"range\":\"BASE\"}", "turn")),
                new ToolAvailability(true, true, true), 3, 3).getFirst();
    }

    AgentToolDispatcher dispatcher(AgentToolExecutor... executors) {
        return new AgentToolDispatcher(registry, List.of(executors), new AgenticDebugLogger());
    }

    ToolResult run(AgentToolDispatcher dispatcher, ToolRegistry.ValidatedAgentCall call) {
        return dispatcher.execute(call, context(run), new HashMap<>(), new HashMap<>());
    }

    @Test
    void successfulOutcomeBecomesAValidatedResultAndConsumesOneToolCall() {
        var fake = new Fake();

        ToolResult result = run(dispatcher(fake), call("a", "q"));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.callId()).isEqualTo("a");
        assertThat(result.evidenceRefs()).containsExactly("C1");
        assertThat(result.errorCode()).isNull();
        assertThat(result.stats()).containsEntry("addedCount", 1);
        assertThat(run.stats().get("toolCalls")).isEqualTo(1);
    }

    @Test
    void expectedToolFailuresBecomeErrorResultsAndTheRunContinues() {
        var fake = new Fake();
        fake.behavior = () -> {
            throw new AgentToolExecutor.ToolFailure("retrieval-budget-exhausted");
        };

        ToolResult result = run(dispatcher(fake), call("a", "q"));

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.errorCode()).isEqualTo("retrieval-budget-exhausted");
        assertThat(result.evidenceRefs()).isEmpty();
    }

    @Test
    void unexpectedFailuresAreMappedWithoutLeakingDetails() {
        var fake = new Fake();
        fake.behavior = () -> {
            throw new IllegalStateException("secret internals");
        };
        assertThat(run(dispatcher(fake), call("a", "q")).errorCode()).isEqualTo("tool-failed");

        fake.behavior = () -> {
            throw new ConcurrentRecallService.RetrievalBranchException("both branches failed");
        };
        assertThat(run(dispatcher(fake), call("b", "q")).errorCode()).isEqualTo("retrieval-failed");
    }

    @Test
    void cancellationAndAuthorizationChangesAbortTheWholeRun() {
        var cancelled = new Fake();
        cancelled.behavior = () -> {
            run.close();
            throw new RunFailure("boom");
        };
        assertThatThrownBy(() -> run(dispatcher(cancelled), call("a", "q")))
                .isInstanceOf(RunFailure.class).hasMessage("cancelled");

        var stale = new Fake();
        stale.behavior = () -> {
            throw new HybridRetrievalOrchestrator.StaleScopeException();
        };
        assertThatThrownBy(() -> dispatcher(stale).execute(call("b", "q"), context(run(AgenticLimits.defaults())),
                new HashMap<>(), new HashMap<>()))
                .isInstanceOf(RunFailure.class).hasMessage("authorization-changed");
    }

    @Test
    void replayIsDeduplicatedConflictsAreRejectedAndRevocationIsNeverBypassed() {
        var fake = new Fake();
        var dispatcher = dispatcher(fake);
        var completed = new HashMap<String, ToolResult>();
        var identities = new HashMap<String, String>();
        var context = context(run);
        var first = call("same", "q");

        ToolResult original = dispatcher.execute(first, context, completed, identities);
        ToolResult replay = dispatcher.execute(first, context, completed, identities);

        assertThat(replay).isSameAs(original);
        assertThat(fake.calls).isEqualTo(1);
        assertThat(run.stats().get("toolCalls")).isEqualTo(1);

        assertThatThrownBy(() -> dispatcher.execute(call("same", "different"), context, completed, identities))
                .isInstanceOf(RunFailure.class).hasMessage("conflicting-call-id");

        version.incrementAndGet();
        assertThatThrownBy(() -> dispatcher.execute(first, context, completed, identities))
                .isInstanceOf(RunFailure.class).hasMessage("authorization-changed");
        assertThat(fake.calls).isEqualTo(1);
    }

    @Test
    void toolCallBudgetIsEnforced() {
        var limits = new AgenticLimits(3, 2, 32, 1, 3, 128, Duration.ofSeconds(300), 256, 16, 6, 6, 12, 12,
                Duration.ofSeconds(30));
        var limited = run(limits);
        var fake = new Fake();
        var dispatcher = dispatcher(fake);
        var completed = new HashMap<String, ToolResult>();
        var identities = new HashMap<String, String>();

        dispatcher.execute(call("a", "q"), context(limited), completed, identities);

        assertThatThrownBy(() -> dispatcher.execute(call("b", "other"), context(limited), completed, identities))
                .isInstanceOf(RunFailure.class).hasMessage("tool-budget-exhausted");
        assertThat(fake.calls).isEqualTo(1);
    }

    @Test
    void referencesAndDegradationsAreDeduplicatedAndBounded() {
        var fake = new Fake();
        List<String> refs = new ArrayList<>(IntStream.range(0, 60).mapToObj(i -> "C" + i).toList());
        refs.add("C1");
        refs.add(" ");
        List<String> degradations = IntStream.range(0, 30).mapToObj(i -> "degraded-" + i).toList();
        fake.behavior = () -> new AgentToolExecutor.Outcome(refs, degradations, Map.of());

        ToolResult result = run(dispatcher(fake), call("a", "q"));

        assertThat(result.evidenceRefs()).hasSize(40).doesNotHaveDuplicates();
        assertThat(result.degradations()).hasSize(20);
    }

    @Test
    void missingExecutorMeansTheToolIsUnavailable() {
        assertThatThrownBy(() -> run(dispatcher(), call("a", "q")))
                .isInstanceOf(RunFailure.class).hasMessage("tool-unavailable");
    }

    @Test
    void executorForAnUnknownToolCannotBeRegistered() {
        var rogue = new Fake() {
            @Override
            public String name() {
                return "rm_rf";
            }
        };
        assertThatThrownBy(() -> dispatcher(rogue)).isInstanceOf(RunFailure.class).hasMessage("unknown-tool");
        assertThatThrownBy(() -> dispatcher(new Fake(), new Fake()))
                .isInstanceOf(IllegalStateException.class).hasMessage("duplicate tool executor");
    }

    @Test
    void debugLogCoversToolStartAndCompletionWithoutEvidenceText() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("kwiki.agentic.debug");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            run(dispatcher(new Fake()), call("a", "q"));

            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(logs).contains("\"phase\":\"tool\"", "\"status\":\"start\"", "\"status\":\"complete\"")
                    .contains("search_chunks").contains("\"addedCount\":1");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }
}
