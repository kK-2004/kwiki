package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

class AnswerDirectlyRegistryTest {
    final ToolRegistry registry = new ToolRegistry();
    final ToolAvailability withReroute = new ToolAvailability(true, true, true, true, true);
    static final String VALID = "{\"reason\":\"问题与知识库无关，是实时时间\"}";

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json, "turn");
    }

    private List<String> names(ToolAvailability availability) {
        return registry.available(availability).stream().map(ToolRegistry.ToolDefinition::name).toList();
    }

    @Test
    void isExposedLastAndOnlyWhenRerouteIsAllowed() {
        assertThat(names(withReroute)).endsWith("answer_directly");
        assertThat(names(new ToolAvailability(true, true, true, true, false))).doesNotContain("answer_directly");
        assertThat(names(new ToolAvailability(true, true, true, true))).as("4 参构造 = canReroute=false")
                .doesNotContain("answer_directly");
        assertThat(names(new ToolAvailability(true, true, true))).as("3 参构造 = canReroute=false")
                .doesNotContain("answer_directly");
        assertThat(AgentTools.ALL).contains("answer_directly");
        assertThat(registry.get("answer_directly").inputSchema()).contains("additionalProperties");
        assertThat(registry.get("answer_directly").outputSchema()).contains("evidenceRefs");
    }

    @Test
    void validCallProducesTypedArguments() {
        var validated = registry.validateAgentBatch(List.of(call("a", "answer_directly", VALID)),
                withReroute, 3, 3);

        assertThat(validated).hasSize(1);
        var arguments = (AgentToolArguments.AnswerDirectly) validated.getFirst().arguments();
        assertThat(arguments.reason()).isEqualTo("问题与知识库无关，是实时时间");
        assertThat(arguments.signature()).isEqualTo(arguments.reason());
    }

    @Test
    void mustBeTheOnlyCallOfATurn() {
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("a", "answer_directly", VALID),
                call("b", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")), withReroute, 3, 3))
                .hasMessage("answer_directly_must_be_alone");
    }

    @Test
    void unavailableWhenRerouteIsNotAllowed() {
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(call("a", "answer_directly", VALID)),
                new ToolAvailability(true, true, true, true, false), 3, 3)).hasMessage("tool-unavailable");
    }

    @Test
    void rejectsMissingEmptyOrOversizedReasonAndExtraProperties() {
        for (String arguments : List.of("{}", "{\"reason\":\"\"}", "{\"reason\":\"" + "x".repeat(201) + "\"}",
                "{\"reason\":\"ok\",\"extra\":1}")) {
            assertThatThrownBy(() -> registry.validateAgentBatch(List.of(call("a", "answer_directly", arguments)),
                    withReroute, 3, 3)).as(arguments).hasMessage("invalid-answer-directly-v1");
        }
    }
}
