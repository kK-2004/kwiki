package com.kwiki.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwiki.rag.orchestration.RunFailure;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import java.time.Duration;

class StreamingAnswerLlmAdapterTest {
    @Test
    void requiresCitationsImmediatelyAfterSupportedClaims() {
        assertThat(StreamingAnswerLlmAdapter.evidenceOnlyContract())
                .contains("immediately after the exact claim")
                .contains("Good: 'The phone number is **123456** [P0].'")
                .contains("Bad: 'According to the material [P0]");
    }

    private StreamingAnswerLlmAdapter adapter(String partial, String complete, FinishReason reason) {
        var model = mock(StreamingChatModel.class);
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (partial != null) handler.onPartialResponse(partial);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(complete)).finishReason(reason).build());
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        return new StreamingAnswerLlmAdapter(model);
    }

    @Test
    void acceptsFinalOnlyText() {
        assertThat(adapter(null, "正文", FinishReason.STOP).streamAnswer("q")
                .collectList().block(Duration.ofSeconds(2))).containsExactly("正文");
    }

    @Test
    void doesNotDuplicateStreamedText() {
        assertThat(adapter("正文", "正文", FinishReason.STOP).streamAnswer("q")
                .collectList().block(Duration.ofSeconds(2))).containsExactly("正文");
    }

    @Test
    void reportsTokenLimitWhenThinkingLeavesNoAnswer() {
        assertThatThrownBy(() -> adapter(null, "", FinishReason.LENGTH)
                .streamAnswer("q").collectList().block(Duration.ofSeconds(2)))
                .isInstanceOf(RunFailure.class).hasMessage("answer-token-limit");
    }

    @Test
    void rejectsTruncatedAnswer() {
        assertThatThrownBy(() -> adapter("未完成", "未完成", FinishReason.LENGTH)
                .streamAnswer("q").collectList().block(Duration.ofSeconds(2)))
                .isInstanceOf(RunFailure.class).hasMessage("answer-token-limit");
    }
}
