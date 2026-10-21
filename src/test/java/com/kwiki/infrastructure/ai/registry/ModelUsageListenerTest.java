package com.kwiki.infrastructure.ai.registry;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.ModelUsageRecorder;
import com.kwiki.usage.UsageEvent;
import com.kwiki.usage.UsageSource;
import com.kwiki.wiki.access.AuthorizationScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageListenerTest {
    final ModelUsageRecorder recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
    final ModelUsageListener listener = new ModelUsageListener(recorder, "REWRITE", "dashscope", "qwen-plus");
    final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("问题")).build();

    static RunContext run(long user) {
        return new RunContext("req-1", new AuthorizationScope(user, false, Set.of(1L), Map.of()),
                kb -> 0L, AgenticLimits.defaults(), Map.of());
    }

    @Test
    void successfulCallRecordsTokensSourceUserAndRequest() throws Exception {
        Map<Object, Object> attributes = new HashMap<>();
        try (var bound = run(42L).bind()) {
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        }
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("答"))
                .tokenUsage(new TokenUsage(120, 30)).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.source()).isEqualTo(UsageSource.ONLINE);
        assertThat(event.step()).isEqualTo("REWRITE");
        assertThat(event.provider()).isEqualTo("dashscope");
        assertThat(event.model()).isEqualTo("qwen-plus");
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.requestId()).isEqualTo("req-1");
        assertThat(event.inputTokens()).isEqualTo(120);
        assertThat(event.outputTokens()).isEqualTo(30);
        assertThat(event.success()).isTrue();
    }

    @Test
    void missingTokenUsageStillCountsTheCall() {
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("答")).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.source()).isEqualTo(UsageSource.BACKGROUND);
        assertThat(event.inputTokens()).isZero();
        assertThat(event.success()).isTrue();
    }

    @Test
    void failureIsRecordedWithErrorType() {
        Map<Object, Object> attributes = new HashMap<>();
        try (var scope = ModelUsageContext.open(UsageSource.EVAL, 7L)) {
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        }
        listener.onError(new ChatModelErrorContext(new HttpException(400, "Arrearage"), request,
                ModelProvider.OPEN_AI, attributes));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.source()).isEqualTo(UsageSource.EVAL);
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.success()).isFalse();
        assertThat(event.errorType()).isEqualTo("HttpException");
    }

    @Test
    void responseWithoutRequestContextFallsBackToCurrentResolution() {
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("答"))
                .tokenUsage(new TokenUsage(1, 1)).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, new HashMap<>()));
        assertThat(recorder.drain()).hasSize(1);
    }
}
