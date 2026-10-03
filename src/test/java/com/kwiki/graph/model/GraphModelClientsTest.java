package com.kwiki.graph.model;

import com.kwiki.graph.config.GraphModelProperties;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 图构建模型端点的问答模型继承、覆盖与可用性判定。 */
class GraphModelClientsTest {

    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(45);

    private static ExternalServicesProperties properties(String answerBaseUrl, String answerApiKey,
                                                         String answerModel) {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("https://files.example.com", "app-token",
                        null, null, Duration.ofSeconds(5), Duration.ofSeconds(60)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm(answerBaseUrl, answerApiKey, answerModel,
                        ANSWER_TIMEOUT),
                new ExternalServicesProperties.QwenEmbedding(
                        "https://dashscope.example.com/compatible-mode/v1", "embed-key",
                        "text-embedding-model", 1024, Duration.ofSeconds(30)),
                ExternalServicesProperties.unusedVisionModel());
    }

    private static GraphModelProperties models(GraphModelProperties.Model extraction,
                                               GraphModelProperties.Model summary) {
        return new GraphModelProperties(extraction, summary);
    }

    @Test
    void 只配置问答模型时两端点可用且继承问答模型名() {
        GraphModelClients clients = new GraphModelClients(
                models(null, null), properties("https://llm.example.com/v1", "answer-key", "answer-model"));

        assertThat(clients.extraction()).isPresent();
        assertThat(clients.summary()).isPresent();
        assertThat(clients.extraction().orElseThrow().modelName()).isEqualTo("answer-model");
        assertThat(clients.summary().orElseThrow().modelName()).isEqualTo("answer-model");
        assertThat(clients.missing("extraction")).isEmpty();
        assertThat(clients.missing("summary")).isEmpty();
    }

    @Test
    void 继承问答模型的超时与缺省并发频率重试() {
        GraphModelClients clients = new GraphModelClients(
                models(null, null), properties("https://llm.example.com/v1", "answer-key", "answer-model"));
        ModelEndpoint extraction = clients.extraction().orElseThrow();
        ModelEndpoint summary = clients.summary().orElseThrow();

        // 抽取 4 / 5.0 / 2，摘要 2 / 2.0 / 2
        assertThat(extraction.limiter().interval()).isEqualTo(Duration.ofMillis(200));
        assertThat(summary.limiter().interval()).isEqualTo(Duration.ofMillis(500));
        assertThat(extraction.maxRetries()).isEqualTo(2);
        assertThat(summary.maxRetries()).isEqualTo(2);
    }

    @Test
    void 单独配置抽取模型名时覆盖问答模型() {
        GraphModelClients clients = new GraphModelClients(
                models(new GraphModelProperties.Model(null, null, "extractor-x", null,
                        8, 10.0, 4), null),
                properties("https://llm.example.com/v1", "answer-key", "answer-model"));

        ModelEndpoint extraction = clients.extraction().orElseThrow();
        assertThat(extraction.modelName()).isEqualTo("extractor-x");
        assertThat(clients.summary().orElseThrow().modelName()).isEqualTo("answer-model");
        assertThat(extraction.maxRetries()).isEqualTo(4);
    }

    @Test
    void 问答模型缺密钥且未单独配置时抽取端点不可用() {
        GraphModelClients clients = new GraphModelClients(
                models(null, null), properties("https://llm.example.com/v1", "", "answer-model"));

        assertThat(clients.extraction()).isEmpty();
        assertThat(clients.summary()).isEmpty();
        assertThat(clients.missing("extraction"))
                .contains("kwiki.graph.models.extraction.api-key");
        assertThat(clients.missing("summary")).contains("kwiki.graph.models.summary.api-key");
    }

    @Test
    void 问答模型缺密钥时单独配置的抽取密钥补齐缺口() {
        GraphModelClients clients = new GraphModelClients(
                models(new GraphModelProperties.Model("https://graph.example.com/v1", "graph-key",
                        null, null, null, null, null), null),
                properties("https://llm.example.com/v1", "", "answer-model"));

        assertThat(clients.extraction()).isPresent();
        assertThat(clients.extraction().orElseThrow().modelName()).isEqualTo("answer-model");
        assertThat(clients.missing("extraction")).isEmpty();
        // 摘要端点没有单独配置，仍然缺失
        assertThat(clients.missing("summary")).contains("kwiki.graph.models.summary.api-key");
    }
}
