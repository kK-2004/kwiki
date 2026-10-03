package com.kwiki.graph.plugin;

import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.CommunitySummaryInput;
import com.kwiki.graph.CommunitySummaryValidator;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.model.ModelEndpoint;
import com.kwiki.graph.model.RateLimiter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 首个大模型社区摘要插件的解析与错误分类。 */
class LlmCommunitySummarizerV1Test {

    private static final String VALID_JSON = """
            {"title":"图存储社区","summary":"Kwiki 使用 ArcadeDB 存储图谱。",
             "keywords":["图谱","存储"],"coreConcepts":["社区检测"],
             "importantRelations":["Kwiki USES ArcadeDB"],
             "questionTypes":["kwiki 用什么存储图谱"],
             "sourceRefs":[{"sourceChunkId":"%s","start":0,"end":10}]}"""
            .formatted(sourceChunkId());

    private static String sourceChunkId() {
        return input().representativeSources().get(0).sourceChunkId();
    }

    private static CommunitySummaryInput input() {
        GraphSourceChunk source = new GraphSourceChunk(7L, "PAGE", 42L, 3L, 1, 5,
                "parser-1", "chunker-1", "kb7:page:42:chunk:0", "hash-1");
        return new CommunitySummaryInput(7L, 2L, "c0", List.of(), List.of(),
                List.of(source), "Kwiki 使用 ArcadeDB 存储图谱。", LlmCommunitySummarizerV1.PROMPT_VERSION);
    }

    private static LlmCommunitySummarizerV1 summarizerReturning(String text) {
        ChatModel stub = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
        return new LlmCommunitySummarizerV1(null,
                new ModelEndpoint(stub, new RateLimiter(2, 0), 2, "summary-model"));
    }

    @Test
    void 合法输出解析为摘要并通过校验() {
        LlmCommunitySummarizerV1 summarizer = summarizerReturning(VALID_JSON);
        CommunitySummaryInput input = input();

        CommunitySummary summary = summarizer.summarize(input);

        assertThat(summary.title()).isEqualTo("图存储社区");
        assertThat(summary.keywords()).containsExactly("图谱", "存储");
        assertThat(summary.sourceRefs()).hasSize(1);
        assertThat(summary.sourceRefs().get(0).sourceChunkId()).isEqualTo(sourceChunkId());
        CommunitySummaryValidator.requireValid(input, summary);
    }

    @Test
    void 非法JSON永久失败() {
        assertThatThrownBy(() -> summarizerReturning("not json").summarize(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SUMMARY_INVALID_JSON");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void 缺少标题或正文的输出永久失败() {
        assertThatThrownBy(() -> summarizerReturning(
                "{\"summary\":\"只有正文\"}").summarize(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("SUMMARY_INVALID_JSON"));
    }

    @Test
    void 模型名与提示词版本写入缓存身份() {
        LlmCommunitySummarizerV1 summarizer = summarizerReturning(VALID_JSON);

        assertThat(summarizer.modelName()).isEqualTo("summary-model");
        assertThat(summarizer.promptVersion()).isEqualTo("community-summary-v1");
        assertThat(summarizer.id()).isEqualTo("kwiki-summarizer-1");
    }
}
