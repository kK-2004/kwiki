package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwiki.rag.orchestration.*;
import com.kwiki.rag.retrieval.*;
import com.kwiki.wiki.access.AuthorizationScope;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class ManualToolDispatcherTest {
    @Test
    void replayIsDeduplicatedButNeverBypassesRevocation() {
        var registry = new ToolRegistry();
        var retrieval = mock(HybridRetrievalOrchestrator.class);
        var budgets = new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5));
        when(retrieval.budgets()).thenReturn(budgets);
        var dispatcher = new ManualToolDispatcher(registry, retrieval);
        var version = new AtomicLong(1);
        var run =
                new RunContext(
                        "r",
                        new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L)),
                        id -> version.get(),
                        AgenticLimits.defaults(),
                        Map.of());
        var args = new SearchArguments(List.of("q"), RetrievalStrategy.BM25, 20);
        var call =
                registry.validateBatch(
                                List.of(
                                        new ToolCall(
                                                "id",
                                                "es_search",
                                                ToolRegistry.json(args),
                                                "turn")),
                                budgets,
                                3)
                        .getFirst();
        var identity = new HashMap<String, String>();
        identity.put("id", "es_search:" + args.signature());
        var cached =
                new ManualToolDispatcher.Execution(
                        new ToolResult("id", "SUCCESS", List.of(), List.of(), null, Map.of()),
                        List.of());
        var completed = new HashMap<String, ManualToolDispatcher.Execution>();
        completed.put("id", cached);
        assertThat(
                        dispatcher.execute(
                                call,
                                run,
                                new HybridRetrievalOrchestrator.Accumulation(),
                                completed,
                                identity))
                .isSameAs(cached);
        assertThat(run.stats().get("toolCalls")).isEqualTo(0);
        identity.put("id", "different");
        assertThatThrownBy(
                        () ->
                                dispatcher.execute(
                                        call,
                                        run,
                                        new HybridRetrievalOrchestrator.Accumulation(),
                                        completed,
                                        identity))
                .hasMessage("conflicting-call-id");
        version.incrementAndGet();
        assertThatThrownBy(
                        () ->
                                dispatcher.execute(
                                        call,
                                        run,
                                        new HybridRetrievalOrchestrator.Accumulation(),
                                        completed,
                                        identity))
                .hasMessage("authorization-changed");
        verify(retrieval, never())
                .retrieve(any(), any(SearchArguments.class), any(), anyInt(), anyLong());
    }
}
