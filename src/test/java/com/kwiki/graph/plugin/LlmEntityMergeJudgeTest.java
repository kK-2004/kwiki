package com.kwiki.graph.plugin;

import com.kwiki.graph.GraphEntityMergeJudge;
import com.kwiki.graph.GraphEntityRegistryPort;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.model.ModelEndpoint;
import com.kwiki.graph.model.RateLimiter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 争议实体合并的大模型判定：解析结论，任何异常或无结论都视为无法判定。 */
class LlmEntityMergeJudgeTest {

    private final List<String> prompts = new ArrayList<>();

    private static final GraphEntityMergeJudge.Candidate CANDIDATE = new GraphEntityMergeJudge.Candidate(
            "k8s", GraphEntityType.TECHNOLOGY, List.of(), "用 k8s 部署服务");
    private static final GraphEntityRegistryPort.RegisteredEntity EXISTING = new GraphEntityRegistryPort.RegisteredEntity(
            "e_1", "Kubernetes", GraphEntityType.TECHNOLOGY, List.of("K8s"), "Kubernetes 编排容器");

    private LlmEntityMergeJudge judge(String reply, boolean enabled) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                prompts.add(messages.get(messages.size() - 1).toString());
                if (reply == null) {
                    throw new IllegalStateException("model down");
                }
                return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
            }
        };
        ModelEndpoint endpoint = new ModelEndpoint(model, new RateLimiter(1, 0), 0, "judge-model");
        return new LlmEntityMergeJudge(() -> Optional.of(endpoint), enabled);
    }

    @Test
    void parsesTheVerdictAndSendsBothEntitiesWithTheirSnippets() {
        var verdict = judge("```json\n{\"same\": true, \"reason\": \"K8s 是 Kubernetes 的缩写\"}\n```", true)
                .judge(CANDIDATE, EXISTING);

        assertThat(verdict).hasValueSatisfying(value -> {
            assertThat(value.sameEntity()).isTrue();
            assertThat(value.reason()).contains("缩写");
            assertThat(value.judge()).isEqualTo("llm:judge-model");
        });
        assertThat(prompts).singleElement().asString()
                .contains("k8s", "Kubernetes", "用 k8s 部署服务", "Kubernetes 编排容器");
    }

    @Test
    void missingVerdictFailureOrDisabledMeansUndecidable() {
        assertThat(judge("{\"reason\": \"不确定\"}", true).judge(CANDIDATE, EXISTING)).isEmpty();
        assertThat(judge("not json", true).judge(CANDIDATE, EXISTING)).isEmpty();
        assertThat(judge(null, true).judge(CANDIDATE, EXISTING)).isEmpty();
        assertThat(judge("{\"same\": true}", false).judge(CANDIDATE, EXISTING)).isEmpty();
        assertThat(new LlmEntityMergeJudge(Optional::empty, true).judge(CANDIDATE, EXISTING)).isEmpty();
    }
}
