package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwiki.rag.routing.Intent;
import com.kwiki.rag.routing.PriorRoute;
import com.kwiki.rag.routing.RouteContext;
import com.kwiki.rag.routing.RouteSource;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

/** 路由 LLM 提示携带上一轮路由与近期用户消息（HTTP 桩契约见 RouterLlmAdapterTest）。 */
class RouterLlmAdapterContextTest {

    private static final String DECISION = """
            {"intent":"KNOWLEDGE_QA","needsRetrieval":true,"rewriteMode":"NONE","subqueries":[],"confidence":0.9}
            """;

    @Test
    @SuppressWarnings("unchecked")
    void promptCarriesPriorRouteAndRecentUserMessages() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(DECISION)).build());
        RouterLlmAdapter adapter = new RouterLlmAdapter(model);
        RouteContext context = new RouteContext(
                new PriorRoute(Intent.KNOWLEDGE_QA, RouteSource.RULE, true, 1.0),
                List.of("介绍一下知识库", "怎么导出页面"), false);

        var decision = adapter.askRouter("它怎么用?", "anaphora-with-context", context);

        assertThat(decision).isPresent();
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        String user = ((UserMessage) captor.getValue().get(1)).singleText();
        assertThat(user).contains("Query: 它怎么用?")
                .contains("Rule trace: anaphora-with-context")
                .contains("Previous turn route: intent=KNOWLEDGE_QA needsRetrieval=true source=RULE")
                .contains("- 介绍一下知识库")
                .contains("- 怎么导出页面");
        assertThat(((SystemMessage) captor.getValue().get(0)).text())
                .contains("clarify").contains("needsRetrieval");
    }

    @Test
    void twoArgumentOverloadUsesAnEmptyContext() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(DECISION)).build());

        assertThat(new RouterLlmAdapter(model).askRouter("什么是知识库", "no-rule-match")).isPresent();
    }

    @Test
    void emptyContextRendersNoPreviousRoute() {
        assertThat(RouterLlmAdapter.userPrompt("q", "no-rule-match", RouteContext.EMPTY))
                .contains("Previous turn route: none")
                .doesNotContain("Recent user messages");
    }

    @Test
    void invalidModelOutputYieldsEmpty() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("not json")).build());

        assertThat(new RouterLlmAdapter(model).askRouter("q", "t")).isEmpty();
    }
}
