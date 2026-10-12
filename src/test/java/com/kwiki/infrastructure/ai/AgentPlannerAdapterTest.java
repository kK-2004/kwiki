package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.orchestration.RunFailure;
import com.kwiki.rag.tool.AgentPlannerPort;
import com.kwiki.rag.tool.RetrievalPlannerPort;
import com.kwiki.rag.tool.ToolAvailability;
import com.kwiki.rag.tool.ToolCall;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.rag.tool.ToolResult;
import com.kwiki.wiki.access.AuthorizationScope;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

class AgentPlannerAdapterTest {
    final ToolRegistry registry = new ToolRegistry();
    final List<ToolRegistry.ToolDefinition> tools = registry.available(new ToolAvailability(true, true, true));

    ChatModel model(AiMessage reply) {
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(reply).build());
        return model;
    }

    AgentPlannerPort.Request request(List<RetrievalPlannerPort.ToolExchange> history, String error) {
        return new AgentPlannerPort.Request(
                Map.of("question", "如何部署 kwiki", "poolSize", 3), tools, 3, history, error);
    }

    ChatRequest captured(ChatModel model) {
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        return captor.getValue();
    }

    @Test
    void nativeModeExposesEveryAvailableToolAndReturnsPlannedCallsWithoutExecutingThem() {
        var model = model(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("c1").name("search_chunks")
                        .arguments("{\"queries\":[\"部署\"],\"strategy\":\"HYBRID\",\"range\":\"BASE\"}").build(),
                ToolExecutionRequest.builder().id("c2").name("rewrite_query")
                        .arguments("{\"mode\":\"EXPANSION\"}").build())));

        List<ToolCall> calls = new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), "native").plan(request(List.of(), ""));

        assertThat(calls).extracting(ToolCall::callId).containsExactly("c1", "c2");
        assertThat(calls).extracting(ToolCall::name).containsExactly("search_chunks", "rewrite_query");
        assertThat(calls.get(0).rawArguments()).contains("部署");
        assertThat(calls).extracting(ToolCall::modelTurnId).doesNotContainNull().hasSize(2);
        assertThat(calls.get(0).modelTurnId()).isEqualTo(calls.get(1).modelTurnId());

        ChatRequest sent = captured(model);
        assertThat(sent.toolSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("search_chunks", "expand_parents", "graph_expand", "rewrite_query");
        assertThat(sent.toolSpecifications().getFirst().parameters().additionalProperties()).isFalse();
        assertThat(((SystemMessage) sent.messages().get(0)).text())
                .contains("at most 3").contains("untrusted").contains("Use native tool calls");
        String user = ((UserMessage) sent.messages().get(1)).singleText();
        assertThat(user).contains("如何部署 kwiki").contains("poolSize").contains("validationError");
    }

    @Test
    void nativeModeWithNoToolRequestsMeansTheEvidenceSuffices() {
        var model = model(AiMessage.from("证据已足够"));

        assertThat(new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), "native").plan(request(List.of(), ""))).isEmpty();
    }

    @Test
    void nativeHistoryClosesEveryCallOfAModelTurnWithItsResult() {
        var model = model(AiMessage.from("done"));
        var history = List.of(
                new RetrievalPlannerPort.ToolExchange(new ToolCall("a", "search_chunks", "{}", "turn-1"),
                        new ToolResult("a", "SUCCESS", List.of("C1"), List.of(), null, Map.of("addedCount", 1))),
                new RetrievalPlannerPort.ToolExchange(new ToolCall("b", "expand_parents", "{}", "turn-1"),
                        new ToolResult("b", "ERROR", List.of(), List.of(), "parent-budget-exhausted", Map.of())));

        new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), "native").plan(request(history, "invalid-tool-count"));

        var messages = captured(model).messages();
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.get(2)).toolExecutionRequests()).hasSize(2);
        assertThat(messages.subList(3, 5)).allMatch(m -> m instanceof ToolExecutionResultMessage);
        assertThat(((ToolExecutionResultMessage) messages.get(3)).id()).isEqualTo("a");
        assertThat(((ToolExecutionResultMessage) messages.get(4)).text()).contains("parent-budget-exhausted");
        assertThat(((UserMessage) messages.get(1)).singleText()).contains("invalid-tool-count");
    }

    @Test
    void jsonModeDescribesEverySchemaAndParsesTheEnvelope() {
        var model = model(AiMessage.from("""
                {"toolCalls":[{"id":"x1","name":"graph_expand","arguments":{"query":"部署"}}]}"""));

        List<ToolCall> calls = new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), "json").plan(request(List.of(), ""));

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().name()).isEqualTo("graph_expand");
        assertThat(calls.getFirst().rawArguments()).isEqualTo("{\"query\":\"部署\"}");
        ChatRequest sent = captured(model);
        assertThat(sent.toolSpecifications()).isNullOrEmpty();
        String system = ((SystemMessage) sent.messages().get(0)).text();
        assertThat(system).contains("\"toolCalls\"").contains("search_chunks", "expand_parents",
                "graph_expand", "rewrite_query").contains("additionalProperties");
    }

    @Test
    void jsonHistoryDoesNotRequireNativeToolRoleSupport() {
        var model = model(AiMessage.from("{\"toolCalls\":[]}"));
        var history = List.of(new RetrievalPlannerPort.ToolExchange(
                new ToolCall("a", "search_chunks", "{}", "turn-1"),
                new ToolResult("a", "SUCCESS", List.of("C1"), List.of(), null, Map.of())));

        assertThat(new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), "json").plan(request(history, ""))).isEmpty();

        var messages = captured(model).messages();
        assertThat(messages).noneMatch(m -> m instanceof ToolExecutionResultMessage);
        assertThat(((UserMessage) messages.get(2)).singleText()).contains("previousToolResults", "SUCCESS");
    }

    @Test
    void malformedOrOversizedEnvelopesAreRejected() {
        for (String bad : List.of(
                "not json",
                "{\"toolCalls\":[],\"extra\":1}",
                "{\"toolCalls\":{}}",
                "{\"toolCalls\":[{\"id\":\"1\",\"name\":\"search_chunks\"}]}",
                "{\"toolCalls\":[{\"id\":\"1\",\"name\":\"search_chunks\",\"arguments\":[]}]}",
                "{\"toolCalls\":[" + oneCall("1") + "," + oneCall("2") + "," + oneCall("3") + "," + oneCall("4") + "]}")) {
            var model = model(AiMessage.from(bad));
            assertThatThrownBy(() -> new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), "json").plan(request(List.of(), "")))
                    .as(bad).isInstanceOf(RunFailure.class);
        }
    }

    private static String oneCall(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"rewrite_query\",\"arguments\":{\"mode\":\"FEEDBACK\"}}";
    }

    @Test
    void theModelCallIsAccountedAgainstTheRunBudget() throws Exception {
        var run = new RunContext("r", new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L)),
                id -> 1, AgenticLimits.defaults(), Map.of());
        var model = model(AiMessage.from("done"));

        try (var bound = run.bind()) {
            new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), "native").plan(request(List.of(), ""));
        }

        assertThat(run.stats().get("modelCalls")).isEqualTo(1);
    }

    @Test
    void invalidToolModeIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new AgentPlannerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed((ChatModel) mock(ChatModel.class)), "magic"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
