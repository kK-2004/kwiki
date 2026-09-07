package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwiki.rag.tool.*;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

class PlannerContinuationTest {
    @Test
    void nativeHistoryClosesEveryCallInOneAssistantTurn() {
        var request = request("native");
        var messages = request.messages();
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.get(2)).toolExecutionRequests()).hasSize(2);
        assertThat(messages.subList(3, 5)).allMatch(m -> m instanceof ToolExecutionResultMessage);
        assertThat(((ToolExecutionResultMessage) messages.get(3)).id()).isEqualTo("a");
        assertThat(((ToolExecutionResultMessage) messages.get(4)).id()).isEqualTo("b");
    }

    @Test
    void jsonHistoryDoesNotRequireNativeToolRoleSupport() {
        var request = request("json");
        assertThat(request.messages()).noneMatch(m -> m instanceof ToolExecutionResultMessage);
        assertThat(((UserMessage) request.messages().get(2)).singleText())
                .contains("previousToolResults", "timeout", "SUCCESS");
    }

    ChatRequest request(String mode) {
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(
                        ChatResponse.builder()
                                .aiMessage(
                                        AiMessage.from(
                                                mode.equals("json")
                                                        ? "{\"toolCalls\":[]}"
                                                        : "done"))
                                .build());
        var history =
                List.of(
                        new RetrievalPlannerPort.ToolExchange(
                                new ToolCall("a", "es_search", "{}", "turn"),
                                new ToolResult(
                                        "a", "SUCCESS", List.of("P1"), List.of(), null, Map.of())),
                        new RetrievalPlannerPort.ToolExchange(
                                new ToolCall("b", "es_search", "{}", "turn"),
                                new ToolResult(
                                        "b", "ERROR", List.of(), List.of(), "timeout", Map.of())));
        new RetrievalPlannerAdapter(model, new ToolRegistry(), mode)
                .plan("q", List.of("q"), List.of(), history, "");
        var captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captured.capture());
        return captured.getValue();
    }
}
