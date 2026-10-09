package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.retrieval.RetrievalStrategy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class AgentToolRegistryTest {
    final ToolRegistry registry = new ToolRegistry();
    final ToolAvailability all = new ToolAvailability(true, true, true);

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json, "turn");
    }

    static String search(String queries, String strategy, String range) {
        return "{\"queries\":" + queries + ",\"strategy\":\"" + strategy + "\",\"range\":\"" + range + "\"}";
    }

    @Test
    void availabilityDrivesWhichToolsAreExposed() {
        assertThat(names(new ToolAvailability(false, false, true)))
                .containsExactly("search_chunks", "rewrite_query");
        assertThat(names(new ToolAvailability(false, true, true)))
                .containsExactly("search_chunks", "expand_parents", "rewrite_query");
        assertThat(names(new ToolAvailability(true, true, true)))
                .containsExactly("search_chunks", "expand_parents", "graph_expand", "rewrite_query");
        assertThat(names(new ToolAvailability(true, false, false)))
                .as("池为空时不暴露 expand_parents / graph_expand；预算用尽不暴露 rewrite_query")
                .containsExactly("search_chunks");
    }

    private List<String> names(ToolAvailability availability) {
        return registry.available(availability).stream().map(ToolRegistry.ToolDefinition::name).toList();
    }

    @Test
    void everyToolHasAClosedInputSchemaAndTheV2ResultSchema() {
        for (String name : AgentTools.ALL) {
            var definition = registry.get(name);
            assertThat(definition.inputSchema()).contains("additionalProperties");
            assertThat(definition.description()).isNotBlank();
            assertThat(definition.outputSchema()).contains("evidenceRefs");
        }
    }

    @Test
    void validBatchProducesTypedArguments() {
        var validated = registry.validateAgentBatch(List.of(
                call("a", "search_chunks", search("[\"Kwiki 部署\",\"备份\"]", "HYBRID", "BASE")),
                call("b", "expand_parents", "{\"childChunkKeys\":[\"C1\"],\"range\":\"EXPANDED\"}"),
                call("c", "graph_expand", "{\"query\":\"部署流程\"}"),
                call("d", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")), all, 4, 3);

        var searchArgs = (AgentToolArguments.SearchChunks) validated.get(0).arguments();
        assertThat(searchArgs.queries()).containsExactly("kwiki 部署", "备份"); // 归一化：小写、合并空白
        assertThat(searchArgs.strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(searchArgs.range()).isEqualTo(AgentToolArguments.Range.BASE);
        var expand = (AgentToolArguments.ExpandParents) validated.get(1).arguments();
        assertThat(expand.childChunkKeys()).containsExactly("C1");
        assertThat(expand.range()).isEqualTo(AgentToolArguments.Range.EXPANDED);
        assertThat(((AgentToolArguments.GraphExpand) validated.get(2).arguments()).query()).isEqualTo("部署流程");
        assertThat(((AgentToolArguments.RewriteQuery) validated.get(3).arguments()).mode())
                .isEqualTo(AgentToolArguments.RewriteKind.FEEDBACK);
    }

    @Test
    void optionalArgumentsDefaultSensibly() {
        var validated = registry.validateAgentBatch(List.of(
                call("a", "expand_parents", "{}"),
                call("b", "graph_expand", "{}")), all, 3, 3);

        var expand = (AgentToolArguments.ExpandParents) validated.get(0).arguments();
        assertThat(expand.childChunkKeys()).isEmpty();
        assertThat(expand.range()).isEqualTo(AgentToolArguments.Range.BASE);
        assertThat(((AgentToolArguments.GraphExpand) validated.get(1).arguments()).query()).isNull();
    }

    @Test
    void signaturesAreStableAcrossArgumentOrder() {
        var first = new AgentToolArguments.SearchChunks(List.of("b", "a"), RetrievalStrategy.BM25,
                AgentToolArguments.Range.BASE);
        var second = new AgentToolArguments.SearchChunks(List.of("a", "b"), RetrievalStrategy.BM25,
                AgentToolArguments.Range.BASE);
        assertThat(first.signature()).isEqualTo(second.signature());
        assertThat(first.signature()).isNotEqualTo(new AgentToolArguments.SearchChunks(
                List.of("a", "b"), RetrievalStrategy.VECTOR, AgentToolArguments.Range.BASE).signature());
    }

    @Test
    void schemaViolationsAreRejected() {
        for (ToolCall bad : List.of(
                call("x", "search_chunks", search("[\"q\"]", "DSL", "BASE")),
                call("x", "search_chunks", search("[\"q\"]", "BM25", "HUGE")),
                call("x", "search_chunks", "{\"queries\":[\"q\"],\"strategy\":\"BM25\"}"),
                call("x", "search_chunks", "{\"queries\":[\"q\"],\"strategy\":\"BM25\",\"range\":\"BASE\",\"scope\":{}}"),
                call("x", "search_chunks", search("[]", "BM25", "BASE")),
                call("x", "search_chunks", search("[\"a\",\"b\",\"c\",\"d\"]", "BM25", "BASE")),
                call("x", "search_chunks", "{\"queries\":[\"q\"],\"strategy\":\"BM25\",\"strategy\":\"VECTOR\",\"range\":\"BASE\"}"),
                call("x", "expand_parents", "{\"kbId\":3}"),
                call("x", "graph_expand", "{\"query\":\"\"}"),
                call("x", "rewrite_query", "{}"),
                call("x", "rewrite_query", "{\"mode\":\"REWRITE_ALL\"}"))) {
            assertThatThrownBy(() -> registry.validateAgentBatch(List.of(bad), all, 3, 3))
                    .as(bad.rawArguments())
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("x", "expand_parents", "{\"childChunkKeys\":" + manyKeys(21) + "}")), all, 3, 3))
                .isInstanceOf(RuntimeException.class);
    }

    private static String manyKeys(int count) {
        StringBuilder keys = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            keys.append(i == 0 ? "" : ",").append("\"K").append(i).append("\"");
        }
        return keys.append("]").toString();
    }

    @Test
    void batchLevelRulesAreEnforced() {
        String ok = search("[\"q\"]", "BM25", "BASE");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(), all, 3, 3))
                .hasMessage("invalid-tool-count");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "search_chunks", ok), call("2", "search_chunks", ok),
                call("3", "search_chunks", ok), call("4", "search_chunks", ok)), all, 3, 3))
                .hasMessage("invalid-tool-count");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("dup", "search_chunks", ok), call("dup", "rewrite_query", "{\"mode\":\"EXPANSION\"}")), all, 3, 3))
                .hasMessage("invalid-call-id");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call(" ", "search_chunks", ok)), all, 3, 3))
                .hasMessage("invalid-call-id");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "rewrite_query", "{\"mode\":\"EXPANSION\"}"),
                call("2", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")), all, 3, 3))
                .hasMessage("duplicate-rewrite");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "search_chunks", search("[\"a\",\"b\"]", "BM25", "BASE")),
                call("2", "search_chunks", search("[\"c\",\"d\"]", "BM25", "BASE"))), all, 3, 3))
                .hasMessage("retrieval-budget-exceeded");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "search_chunks", search("[\"a\",\"A \"]", "BM25", "BASE"))), all, 3, 3))
                .as("归一化后重复").hasMessage("invalid-queries");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "search_chunks", search("[\"  \"]", "BM25", "BASE"))), all, 3, 3))
                .hasMessage("invalid-queries");
    }

    @Test
    void unknownAndUnavailableToolsAreRejectedWithDistinctCodes() {
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "delete_everything", "{}")), all, 3, 3)).hasMessage("unknown-tool");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "es_search", "{}")), all, 3, 3))
                .as("旧工具不在 agent 工具集内").hasMessage("unknown-tool");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "graph_expand", "{}")), new ToolAvailability(false, true, true), 3, 3))
                .hasMessage("tool-unavailable");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "expand_parents", "{}")), new ToolAvailability(true, false, true), 3, 3))
                .hasMessage("tool-unavailable");
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("1", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")), new ToolAvailability(true, true, false), 3, 3))
                .hasMessage("tool-unavailable");
    }

    @Test
    void legacyBatchValidationStillOnlyAcceptsEsSearch() {
        var budgets = new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5));
        assertThatThrownBy(() -> registry.validateBatch(List.of(
                call("1", "search_chunks", "{}")), budgets, 3)).hasMessage("unknown-tool");
        assertThat(registry.get("es_search").name()).isEqualTo("es_search");
    }
}
