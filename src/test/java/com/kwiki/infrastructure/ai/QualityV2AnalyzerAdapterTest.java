package com.kwiki.infrastructure.ai;

import com.kwiki.rag.answer.CandidateAnswer;
import com.kwiki.rag.orchestration.AttemptStage;
import com.kwiki.rag.orchestration.RunFailure;
import com.kwiki.rag.quality.QualityV2Input;
import com.kwiki.rag.tool.ToolRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.wiki.access.AuthorizationScope;
import dev.langchain4j.model.chat.response.PartialThinking;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

class QualityV2AnalyzerAdapterTest {
    private static final String VALID = """
        {"relevance":0.9,"coverage":0.9,"faithfulness":0.9,"passed":true,
        "supportedEvidenceIds":[],"unsupportedClaims":[],"missingAspects":[],
        "reasonCode":"supported","reasonSummary":"证据充分"}
        """;
    private QualityV2AnalyzerAdapter adapter(String output) {
        var model = mock(StreamingChatModel.class);
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onPartialThinking(new PartialThinking("核对证据"));
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(output)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return new QualityV2AnalyzerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), new ToolRegistry());
    }
    private QualityV2Input input() {
        return new QualityV2Input("问题", "问题", new CandidateAnswer("c", "回答",
                CandidateAnswer.EvidenceLevel.CHILD, AttemptStage.BASE_CHILD, List.of(), List.of()), List.of());
    }
    @Test void acceptsJsonAndCompleteCodeFence() {
        assertThat(adapter(VALID).assess(input()).passed()).isTrue();
        assertThat(adapter("```json\n" + VALID + "```").assess(input()).passed()).isTrue();
    }
    @Test void streamsThinkingSeparatelyFromTheValidatedAssessment() throws Exception {
        var run = new RunContext("r", new AuthorizationScope(0, false, Set.of(), Map.of()),
                id -> 1, AgenticLimits.defaults(), Map.of());
        var thinking = new ArrayList<String>();
        run.thinkingListener(thinking::add);
        try (run; var binding = run.bind()) {
            assertThat(adapter(VALID).assess(input()).passed()).isTrue();
            assertThat(thinking).containsExactly("核对证据");
        }
    }
    @Test void requestsStrictProviderSideQualitySchema() {
        var model = mock(StreamingChatModel.class);
        var request = new AtomicReference<ChatRequest>();
        doAnswer(call -> {
            request.set(call.getArgument(0));
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(VALID)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        assertThat(new QualityV2AnalyzerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), new ToolRegistry()).assess(input()).passed())
                .isTrue();
        var format = request.get().responseFormat();
        assertThat(format.type()).isEqualTo(
                dev.langchain4j.model.chat.request.ResponseFormatType.JSON);
        assertThat(format.jsonSchema().name()).isEqualTo("quality_v2");
        assertThat(format.jsonSchema().rootElement())
                .isInstanceOf(dev.langchain4j.model.chat.request.json.JsonRawSchema.class);
        var parameters = (dev.langchain4j.model.openai.OpenAiChatRequestParameters)
                request.get().parameters();
        assertThat(parameters.customParameters()).containsEntry("enable_thinking", false);
        var raw = (dev.langchain4j.model.chat.request.json.JsonRawSchema)
                format.jsonSchema().rootElement();
        assertThat(raw.schema()).contains("supportedEvidenceIds", "additionalProperties");
    }
    @Test void retriesHttp400WithJsonObjectAndStillValidatesLocally() {
        var model = mock(StreamingChatModel.class);
        var calls = new AtomicInteger();
        var requests = new ArrayList<ChatRequest>();
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            requests.add(request);
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (calls.getAndIncrement() == 0) {
                handler.onError(new dev.langchain4j.exception.InvalidRequestException(
                        new RunFailure("model-stream-failed",
                                new dev.langchain4j.exception.HttpException(
                                        400, "model request rejected"))));
            } else {
                handler.onCompleteResponse(
                        ChatResponse.builder().aiMessage(AiMessage.from(VALID)).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        assertThat(new QualityV2AnalyzerAdapter(com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), new ToolRegistry()).assess(input()).passed())
                .isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).responseFormat().jsonSchema()).isNotNull();
        assertThat(requests.get(1).responseFormat().type())
                .isEqualTo(dev.langchain4j.model.chat.request.ResponseFormatType.JSON);
        assertThat(requests.get(1).responseFormat().jsonSchema()).isNull();
    }
    @Test void malformedOrSchemaInvalidReviewsAreUnavailable() {
        for (String invalid : List.of("{", "说明" + VALID, "{}", VALID + VALID, VALID.replace("0.9", "2"))) {
            assertThatThrownBy(() -> adapter(invalid).assess(input()))
                    .isInstanceOf(RunFailure.class).hasMessage("qa-unavailable");
        }
    }

    @Test void remembersJsonSchemaRejectionAndUsesJsonObjectDirectlyAfterwards() {
        var model = mock(StreamingChatModel.class);
        var requests = new ArrayList<ChatRequest>();
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            requests.add(request);
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (request.responseFormat().jsonSchema() != null) {
                handler.onError(new dev.langchain4j.exception.InvalidRequestException(
                        new RunFailure("model-stream-failed", new dev.langchain4j.exception.HttpException(400,
                                "model request rejected: This response_format type is unavailable now"))));
            } else {
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(VALID)).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var adapter = new QualityV2AnalyzerAdapter(
                com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), new ToolRegistry());

        assertThat(adapter.assess(input()).passed()).isTrue();
        assertThat(adapter.assess(input()).passed()).isTrue();

        assertThat(requests).hasSize(3); // 第一次：schema 被拒 + object 成功；第二次：直接 object
        assertThat(requests.get(2).responseFormat().jsonSchema()).isNull();
    }

    @Test void unrelatedFailureAfterFallbackIsNotRemembered() {
        var model = mock(StreamingChatModel.class);
        var requests = new ArrayList<ChatRequest>();
        doAnswer(call -> {
            requests.add(call.getArgument(0));
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onError(new dev.langchain4j.exception.InvalidRequestException(
                    new RunFailure("model-stream-failed",
                            new dev.langchain4j.exception.HttpException(400, "context too long"))));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var adapter = new QualityV2AnalyzerAdapter(
                com.kwiki.infrastructure.ai.registry.ModelRegistry.fixed(model), new ToolRegistry());

        assertThatThrownBy(() -> adapter.assess(input())).isInstanceOf(RunFailure.class);
        assertThatThrownBy(() -> adapter.assess(input())).isInstanceOf(RunFailure.class);

        // 回退也失败时不记忆，第二次仍先试 json_schema
        assertThat(requests.get(2).responseFormat().jsonSchema()).isNotNull();
    }

    @Test void schemaViolationsAreReportedForDiagnostics() {
        var registry = new ToolRegistry();
        // reasonCode 用了下划线与大写、缺少 reasonSummary
        String bad = """
            {"relevance":0.9,"coverage":0.9,"faithfulness":0.9,"passed":true,
            "supportedEvidenceIds":[],"unsupportedClaims":[],"missingAspects":[],"reasonCode":"Answer_Supported"}
            """;
        assertThat(registry.violations("quality-v2", bad)).isNotEmpty()
                .anySatisfy(v -> assertThat(v).contains("reasonCode"))
                .anySatisfy(v -> assertThat(v).contains("reasonSummary"));
        assertThat(registry.violations("quality-v2", VALID)).isEmpty();
        assertThat(registry.violations("quality-v2", "不是 JSON")).containsExactly("invalid-json");
    }

    @Test void reasonCodeStyleDeviationsAreNormalisedInsteadOfFailingTheReview() {
        String underscored = """
            {"relevance":1.0,"coverage":1.0,"faithfulness":1.0,"passed":true,
            "supportedEvidenceIds":[],"unsupportedClaims":[],"missingAspects":[],
            "reasonCode":"Answer_Correctly_Identifies Missing__Data","reasonSummary":"证据充分"}
            """;

        var assessment = adapter(underscored).assess(input());

        assertThat(assessment.passed()).isTrue();
        assertThat(assessment.reasonCode()).isEqualTo("answer-correctly-identifies-missing-data");
    }

    @Test void reasonCodeNormalisation() {
        assertThat(QualityV2AnalyzerAdapter.normalizeReasonCode("answer_supported")).isEqualTo("answer-supported");
        assertThat(QualityV2AnalyzerAdapter.normalizeReasonCode("  --Coverage Missing!! ")).isEqualTo("coverage-missing");
        assertThat(QualityV2AnalyzerAdapter.normalizeReasonCode("1st-reason")).isEqualTo("r-1st-reason");
        assertThat(QualityV2AnalyzerAdapter.normalizeReasonCode("证据不足")).isEqualTo("invalid");
        assertThat(QualityV2AnalyzerAdapter.normalizeReasonCode("a".repeat(80))).hasSize(64);
    }
}
