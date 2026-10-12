package com.kwiki.graph.plugin;

import com.kwiki.graph.GraphExtractionInput;
import com.kwiki.graph.GraphExtractionPrompt;
import com.kwiki.graph.GraphExtractionValidator;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.GraphPredicate;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.model.GraphModelClients;
import com.kwiki.graph.model.ModelEndpoint;
import com.kwiki.graph.model.RateLimiter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 首个大模型抽取插件的解析、错误分类与可用性。 */
class LlmGraphExtractorV1Test {

    private static final String CONTENT = "Kwiki uses ArcadeDB for graph storage.";

    private static ChatModel modelReturning(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }

    private static ChatModel modelFailing(RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw failure;
            }
        };
    }

    private static ModelEndpoint endpoint(ChatModel model) {
        return new ModelEndpoint(model, new RateLimiter(2, 0), 2, "test-model");
    }

    private static GraphExtractionInput input() {
        GraphSourceChunk source = new GraphSourceChunk(7L, "PAGE", 42L, 3L, 1, 5,
                "parser-1", "chunker-1", "kb7:page:42:chunk:0", "hash-1");
        return new GraphExtractionInput(source, CONTENT, "", LlmGraphExtractorV1.ID,
                GraphExtractionPrompt.VERSION, "el-v1");
    }

    private static final String VALID_JSON = """
            {"entities":[{"id":"e1","name":"Kwiki","type":"PRODUCT","aliases":["kwiki"]},
             {"id":"e2","name":"ArcadeDB","type":"TECHNOLOGY","aliases":[]}],
             "relations":[{"source":"e1","target":"e2","predicate":"USES","confidence":0.8,"start":0,"end":5}]}""";

    @Test
    void 合法输出解析为实体关系并通过结构校验() {
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null, endpoint(modelReturning(VALID_JSON)));
        GraphExtractionInput input = input();

        var result = extractor.extract(input);

        assertThat(result.entities()).hasSize(2);
        assertThat(result.entities().get(0).entityId()).isEqualTo("e1");
        assertThat(result.entities().get(0).canonicalName()).isEqualTo("Kwiki");
        assertThat(result.entities().get(0).entityType()).isEqualTo(GraphEntityType.PRODUCT);
        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().get(0).predicate()).isEqualTo(GraphPredicate.USES);
        assertThat(result.relations().get(0).sourceRefs().get(0).sourceChunkId())
                .isEqualTo(input.sourceChunk().sourceChunkId());
        assertThat(result.extractorVersion()).isEqualTo(LlmGraphExtractorV1.ID);
        assertThat(result.promptVersion()).isEqualTo(GraphExtractionPrompt.VERSION);
        GraphExtractionValidator.validate(input, result);
    }

    @Test
    void 带围栏的输出被剥离后同样解析() {
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null,
                endpoint(modelReturning("```json\n" + VALID_JSON + "\n```")));

        var result = extractor.extract(input());

        assertThat(result.entities()).hasSize(2);
        assertThat(result.relations()).hasSize(1);
    }

    @Test
    void 未知实体类型与关系谓词回退为受控值() {
        String json = """
                {"entities":[{"id":"e1","name":"X","type":"VEHICLE","aliases":[]}],
                 "relations":[{"source":"e1","target":"e1","predicate":"OWNS","confidence":0.5,"start":0,"end":5}]}""";
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null, endpoint(modelReturning(json)));

        var result = extractor.extract(input());

        assertThat(result.entities().get(0).entityType()).isEqualTo(GraphEntityType.OTHER);
        assertThat(result.relations().get(0).predicate()).isEqualTo(GraphPredicate.RELATED_TO);
    }

    @Test
    void 越界引用区间的关系被丢弃且不影响其余() {
        String json = """
                {"entities":[{"id":"e1","name":"Kwiki","type":"PRODUCT","aliases":[]},
                 {"id":"e2","name":"ArcadeDB","type":"TECHNOLOGY","aliases":[]}],
                 "relations":[{"source":"e1","target":"e2","predicate":"USES","confidence":0.9,"start":0,"end":999},
                              {"source":"e1","target":"e2","predicate":"USES","confidence":0.9,"start":0,"end":5}]}""";
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null, endpoint(modelReturning(json)));

        var result = extractor.extract(input());

        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().get(0).sourceRefs().get(0).endOffset()).isEqualTo(5);
    }

    @Test
    void 非法JSON永久失败() {
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null,
                endpoint(modelReturning("这不是 JSON")));

        assertThatThrownBy(() -> extractor.extract(input()))
                .isInstanceOf(GraphBuildFailure.class)
                .satisfies(failure -> {
                    GraphBuildFailure gbf = (GraphBuildFailure) failure;
                    assertThat(gbf.code()).isEqualTo("EXTRACTION_INVALID_JSON");
                    assertThat(gbf.retryable()).isFalse();
                });
    }

    @Test
    void 模型超时与5xx可重试() {
        LlmGraphExtractorV1 serverError = new LlmGraphExtractorV1(null,
                endpoint(modelFailing(new HttpException(500, "boom"))));
        LlmGraphExtractorV1 rateLimited = new LlmGraphExtractorV1(null,
                endpoint(modelFailing(new HttpException(429, "slow down"))));

        assertThatThrownBy(() -> serverError.extract(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MODEL_UNAVAILABLE");
                    assertThat(failure.retryable()).isTrue();
                });
        assertThatThrownBy(() -> rateLimited.extract(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
    }

    @Test
    void 模型4xx永久失败不重试() {
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(null,
                endpoint(modelFailing(new HttpException(400, "bad request"))));

        assertThatThrownBy(() -> extractor.extract(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MODEL_REQUEST_REJECTED");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void 抽取模型未配置时不可用并给出缺失键() {
        GraphModelClients clients = new GraphModelClients(com.kwiki.testutil.StubModelRegistry.empty());
        LlmGraphExtractorV1 extractor = new LlmGraphExtractorV1(clients);

        assertThat(extractor.availability().available()).isFalse();
        assertThat(extractor.availability().unavailableReason())
                .contains("缺少配置")
                .contains("GRAPH_EXTRACTION");
        assertThatThrownBy(() -> extractor.extract(input()))
                .isInstanceOfSatisfying(GraphBuildFailure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
    }

}
