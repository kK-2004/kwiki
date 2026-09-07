package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.*;

import com.kwiki.rag.retrieval.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

class ToolRegistryTest {
    final ToolRegistry registry = new ToolRegistry();
    final RetrievalBudgets budgets =
            new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5));

    ToolCall call(String json) {
        return new ToolCall("id", "es_search", json, "turn");
    }

    @Test
    void closedSchemaAndRuntimeLimitsRejectUnsafeCalls() {
        for (String raw :
                List.of(
                        "{\"queries\":[\"q\"],\"strategy\":\"BM25\",\"topK\":20,\"scope\":{}}",
                        "{\"queries\":[\"q\"],\"strategy\":\"BM25\",\"topK\":20,\"topK\":10}",
                        "{\"queries\":[\"q\"],\"strategy\":\"BM25\",\"topK\":100}",
                        "{\"queries\":[\" \"],\"strategy\":\"BM25\",\"topK\":10}",
                        "{\"queries\":[\"q\"],\"strategy\":\"DSL\",\"topK\":10}"))
            assertThatThrownBy(() -> registry.validateBatch(List.of(call(raw)), budgets, 3))
                    .isInstanceOf(RuntimeException.class);
    }

    @Test
    void validCallKeepsTypedArguments() {
        var validated =
                registry.validateBatch(
                        List.of(
                                call(
                                        ToolRegistry.json(
                                                new SearchArguments(
                                                        List.of("kwiki"),
                                                        RetrievalStrategy.BM25,
                                                        20)))),
                        budgets,
                        3);
        assertThat(validated.getFirst().arguments().strategy()).isEqualTo(RetrievalStrategy.BM25);
        assertThat(registry.get("es_search").inputSchema()).contains("additionalProperties");
    }

    @Test
    void duplicateIdsAndAggregateQueriesAreRejected() {
        var args =
                ToolRegistry.json(
                        new SearchArguments(List.of("a", "b"), RetrievalStrategy.HYBRID, 20));
        assertThatThrownBy(
                        () -> registry.validateBatch(List.of(call(args), call(args)), budgets, 3))
                .hasMessage("invalid-call-id");
        assertThatThrownBy(
                        () ->
                                registry.validateBatch(
                                        List.of(
                                                call(args),
                                                new ToolCall(
                                                        "other",
                                                        "es_search",
                                                        ToolRegistry.json(
                                                                new SearchArguments(
                                                                        List.of("c", "d"),
                                                                        RetrievalStrategy.BM25,
                                                                        20)),
                                                        "turn")),
                                        budgets,
                                        3))
                .hasMessage("retrieval-budget-exceeded");
    }
}
