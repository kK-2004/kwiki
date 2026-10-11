package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

class AskUserRegistryTest {
    final ToolRegistry registry = new ToolRegistry();
    final ToolAvailability withAsk = new ToolAvailability(true, true, true, true);

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json, "turn");
    }

    static final String VALID = "{\"question\":\"你想了解哪一个？\",\"options\":["
            + "{\"ref\":\"C1\",\"label\":\"部署手册\"},{\"ref\":\"C2\",\"label\":\"运维手册\"}]}";

    private List<String> names(ToolAvailability availability) {
        return registry.available(availability).stream().map(ToolRegistry.ToolDefinition::name).toList();
    }

    @Test
    void askUserIsExposedLastAndOnlyWhenAllowedAndEvidenceExists() {
        assertThat(names(withAsk)).containsExactly(
                "search_chunks", "expand_parents", "graph_expand", "rewrite_query", "ask_user");
        assertThat(names(new ToolAvailability(true, true, true, false))).doesNotContain("ask_user");
        assertThat(names(new ToolAvailability(true, false, true, true))).doesNotContain("ask_user");
        assertThat(names(new ToolAvailability(true, true, true))).as("3 参构造 = canAsk=false")
                .doesNotContain("ask_user");
        assertThat(AgentTools.ALL).endsWith("ask_user");
        assertThat(registry.get("ask_user").inputSchema()).contains("additionalProperties");
    }

    @Test
    void validCallProducesTypedArguments() {
        var validated = registry.validateAgentBatch(List.of(call("a", "ask_user", VALID)), withAsk, 3, 3);

        var args = (AgentToolArguments.AskUser) validated.getFirst().arguments();
        assertThat(args.question()).isEqualTo("你想了解哪一个？");
        assertThat(args.options()).extracting(AgentToolArguments.AskUser.Option::ref).containsExactly("C1", "C2");
        assertThat(args.options()).extracting(AgentToolArguments.AskUser.Option::label)
                .containsExactly("部署手册", "运维手册");
        assertThat(args.allowFreeText()).as("缺省允许自由输入").isTrue();
    }

    @Test
    void explicitFreeTextFlagAndNoOptionsAreAccepted() {
        var args = (AgentToolArguments.AskUser) registry.validateAgentBatch(List.of(
                call("a", "ask_user", "{\"question\":\"请补充限定条件\",\"allowFreeText\":false}")),
                withAsk, 3, 3).getFirst().arguments();

        assertThat(args.options()).isEmpty();
        assertThat(args.allowFreeText()).isFalse();
    }

    @Test
    void schemaViolationsAreRejected() {
        for (String bad : List.of(
                "{}",
                "{\"question\":\"\"}",
                "{\"question\":\"q\",\"scope\":{}}",
                "{\"question\":\"q\",\"options\":[{\"ref\":\"C1\"}]}",
                "{\"question\":\"q\",\"options\":[{\"ref\":\"C1\",\"label\":\"x\",\"kbId\":3}]}",
                "{\"question\":\"q\",\"options\":[{\"ref\":\"1\",\"label\":\"a\"},{\"ref\":\"2\",\"label\":\"b\"},"
                        + "{\"ref\":\"3\",\"label\":\"c\"},{\"ref\":\"4\",\"label\":\"d\"},{\"ref\":\"5\",\"label\":\"e\"}]}",
                "{\"question\":\"q\",\"allowFreeText\":\"yes\"}")) {
            assertThatThrownBy(() -> registry.validateAgentBatch(List.of(call("a", "ask_user", bad)), withAsk, 3, 3))
                    .as(bad).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void askUserMustBeTheOnlyCallOfATurn() {
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("a", "ask_user", VALID),
                call("b", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")), withAsk, 3, 3))
                .hasMessage("ask_user_must_be_alone");
    }

    @Test
    void unavailableWhenNotAllowed() {
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(call("a", "ask_user", VALID)),
                new ToolAvailability(true, true, true, false), 3, 3)).hasMessage("tool-unavailable");
    }
}
