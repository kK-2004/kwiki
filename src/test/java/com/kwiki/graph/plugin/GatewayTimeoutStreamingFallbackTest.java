package com.kwiki.graph.plugin;

import com.kwiki.graph.GraphExtractionInput;
import com.kwiki.graph.GraphExtractionPrompt;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.model.ModelEndpoint;
import com.kwiki.graph.model.RateLimiter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 网关 524（源站首字节超时）后自动改用流式重发，并记住该端点此后直接走流式。 */
class GatewayTimeoutStreamingFallbackTest {

    private static final String CONTENT = "Kwiki uses ArcadeDB for graph storage.";
    private static final String VALID_JSON = """
            {"entities":[{"id":"e1","name":"Kwiki","type":"PRODUCT","aliases":[]},
             {"id":"e2","name":"ArcadeDB","type":"TECHNOLOGY","aliases":[]}],
             "relations":[{"source":"e1","target":"e2","predicate":"USES","confidence":0.8,"start":0,"end":5}]}""";

    private final AtomicInteger blockingCalls = new AtomicInteger();
    private final AtomicInteger streamingCalls = new AtomicInteger();

    private static GraphExtractionInput input() {
        GraphSourceChunk source = new GraphSourceChunk(7L, "PAGE", 42L, 3L, 1, 5,
                "parser-1", "chunker-1", "kb7:page:42:chunk:0", "hash-1");
        return new GraphExtractionInput(source, CONTENT, "", LlmGraphExtractorV1.ID,
                GraphExtractionPrompt.VERSION, "el-v1");
    }

    private ChatModel blockingFailing(RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                blockingCalls.incrementAndGet();
                throw failure;
            }
        };
    }

    /** 分两段下发 JSON 的流式模型；完成回调不带正文，验证由分片拼出完整回答。 */
    private StreamingChatModel streamingReturning(String text) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                streamingCalls.incrementAndGet();
                int middle = text.length() / 2;
                handler.onPartialResponse(text.substring(0, middle));
                handler.onPartialResponse(text.substring(middle));
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("")).build());
            }
        };
    }

    private StreamingChatModel streamingFailing(RuntimeException failure) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                streamingCalls.incrementAndGet();
                handler.onError(failure);
            }
        };
    }

    private static ModelEndpoint endpoint(ChatModel chat, StreamingChatModel streaming, AtomicBoolean preferred) {
        return new ModelEndpoint(chat, new RateLimiter(2, 0), 2, "glm-5.3-flash", streaming,
                Duration.ofSeconds(5), preferred);
    }

    @Test
    void gatewayTimeoutRetriesTheSameCallOverStreamingAndRemembersIt() {
        AtomicBoolean preferred = new AtomicBoolean();
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null, endpoint(
                blockingFailing(new HttpException(524, "error code: 524")), streamingReturning(VALID_JSON), preferred));

        var first = extractor.extract(input());
        var second = extractor.extract(input());

        assertThat(first.entities()).hasSize(2);
        assertThat(second.relations()).hasSize(1);
        assertThat(preferred).isTrue();
        // 第一次：非流式 524 → 流式；第二次直接流式，不再先等一次 524
        assertThat(blockingCalls).hasValue(1);
        assertThat(streamingCalls).hasValue(2);
    }

    @Test
    void otherServerErrorsDoNotSwitchToStreaming() {
        AtomicBoolean preferred = new AtomicBoolean();
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null, endpoint(
                blockingFailing(new HttpException(520, "error code: 520")), streamingReturning(VALID_JSON), preferred));

        assertThatThrownBy(() -> extractor.extract(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> assertThat(failure.retryable()).isTrue());
        assertThat(preferred).isFalse();
        assertThat(streamingCalls).hasValue(0);
    }

    @Test
    void endpointWithoutStreamingClientKeepsTheOriginalFailure() {
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null, new ModelEndpoint(
                blockingFailing(new HttpException(524, "error code: 524")), new RateLimiter(2, 0), 2, "m"));

        assertThatThrownBy(() -> extractor.extract(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> assertThat(failure.retryable()).isTrue());
    }

    @Test
    void streamingFailureIsClassifiedLikeAnyModelFailure() {
        AtomicBoolean preferred = new AtomicBoolean(true);
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null, endpoint(
                blockingFailing(new IllegalStateException("should not be called")),
                streamingFailing(new com.kwiki.rag.orchestration.RunFailure("timeout")), preferred));

        assertThatThrownBy(() -> extractor.extract(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(GraphBuildFailure.MODEL_TIMEOUT));
        assertThat(blockingCalls).hasValue(0);
    }

    @Test
    void wrappedGatewayTimeoutIsRecognised() {
        assertThat(LlmPluginSupport.gatewayResponseTimeout(
                new RuntimeException(new HttpException(524, "x")))).isTrue();
        assertThat(LlmPluginSupport.gatewayResponseTimeout(new HttpException(504, "x"))).isFalse();
    }
}
