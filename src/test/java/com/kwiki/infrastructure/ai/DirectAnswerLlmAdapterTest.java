package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwiki.rag.answer.DirectAnswerPort;
import com.kwiki.rag.rewrite.ChatTurn;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

class DirectAnswerLlmAdapterTest {

    @SuppressWarnings("unchecked")
    @Test
    void sendsContractAndQuestionAndReturnsModelText() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("Hello, world.")).build());

        String answer = new DirectAnswerLlmAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model)).answer(
                "把“你好世界”翻译成英文",
                List.of(new ChatTurn("USER", "介绍一下知识库")));

        assertThat(answer).isEqualTo("Hello, world.");
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        assertThat(((SystemMessage) captor.getValue().get(0)).text())
                .contains(DirectAnswerPort.ESCALATE_SENTINEL)
                .contains("知识库");
        String user = ((UserMessage) captor.getValue().get(1)).singleText();
        assertThat(user).contains("把“你好世界”翻译成英文").contains("介绍一下知识库");
    }

    @Test
    void sentinelPassesThroughUnchanged() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(DirectAnswerPort.ESCALATE_SENTINEL)).build());

        assertThat(new DirectAnswerLlmAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model)).answer("公司报销制度是什么", List.of()))
                .isEqualTo(DirectAnswerPort.ESCALATE_SENTINEL);
    }
}
