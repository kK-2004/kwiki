package com.kwiki.infrastructure.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在任何客户端装配存在之前，校验每一条外部服务记录的
 * 必填/空值/格式规则，因此连接配置错误在绑定时就会暴露。
 */
class ExternalServicePropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static ExternalServicesProperties valid() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "https://content-center.internal", "kapp-test-token", null, null,
                        Duration.ofSeconds(10), Duration.ofSeconds(600)),
                new ExternalServicesProperties.Elasticsearch(null, "elastic", "secret"),
                new ExternalServicesProperties.AnswerLlm(
                        "https://llm.internal/v1", "sk-answer", "qwen-max", Duration.ofSeconds(120)),
                new ExternalServicesProperties.QwenEmbedding(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "sk-embed", "text-embedding-v4", 1024, Duration.ofSeconds(30)));
    }

    private Set<ConstraintViolation<ExternalServicesProperties>> violationsOf(
            ExternalServicesProperties candidate) {
        return validator.validate(candidate);
    }

    @Test
    void fullyPopulatedPropertiesPassValidation() {
        assertThat(violationsOf(valid())).isEmpty();
    }

    @Test
    void blankOrMalformedContentCenterValuesAreRejected() {
        var props = valid();
        var candidate = new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "not-a-url", "", null, null, null, null),
                props.elasticsearch(), props.answerLlm(), props.qwenEmbedding());
        assertThat(violationsOf(candidate))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("contentCenter.baseUrl", "contentCenter.appToken",
                        "contentCenter.connectTimeout", "contentCenter.requestTimeout")
                .allSatisfy(path -> assertThat(String.valueOf(path))
                        .as("violation reporting stays on property paths, never token values")
                        .doesNotContain("kapp-"));
    }

    @Test
    void blankContentCenterTokenAndBaseUrlAreRejected() {
        var props = valid();
        var candidate = new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        " ", " ", "source", "path", Duration.ofSeconds(10), Duration.ofSeconds(600)),
                props.elasticsearch(), props.answerLlm(), props.qwenEmbedding());
        assertThat(violationsOf(candidate))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("contentCenter.baseUrl", "contentCenter.appToken");
    }

    @Test
    void validContentCenterConfigurationPassesWithoutRequiringSourceOrPath() {
        var candidate = new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "https://content-center.internal", "kapp-real-value-not-asserted",
                        null, null, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                valid().elasticsearch(), valid().answerLlm(), valid().qwenEmbedding());
        assertThat(violationsOf(candidate)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankAnswerLlmValuesAreRejected(String value) {
        var props = valid();
        var candidate = new ExternalServicesProperties(
                props.contentCenter(), props.elasticsearch(),
                new ExternalServicesProperties.AnswerLlm(value, value, value, Duration.ofSeconds(10)),
                props.qwenEmbedding());
        assertThat(violationsOf(candidate))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("answerLlm.baseUrl", "answerLlm.apiKey", "answerLlm.model");
    }

    @Test
    void missingEmbeddingApiKeyIsRejected() {
        var props = valid();
        var candidate = new ExternalServicesProperties(
                props.contentCenter(), props.elasticsearch(), props.answerLlm(),
                new ExternalServicesProperties.QwenEmbedding(
                        props.qwenEmbedding().baseUrl(), " ", "text-embedding-v4", 1024,
                        Duration.ofSeconds(30)));
        assertThat(violationsOf(candidate))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("qwenEmbedding.apiKey");
    }

    @Test
    void embeddingModelIsPinnedToTextEmbeddingV4() {
        var props = valid();
        var candidate = new ExternalServicesProperties(
                props.contentCenter(), props.elasticsearch(), props.answerLlm(),
                new ExternalServicesProperties.QwenEmbedding(
                        props.qwenEmbedding().baseUrl(), "sk-embed", "text-embedding-v3", 1024,
                        Duration.ofSeconds(30)));
        assertThat(violationsOf(candidate))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("qwenEmbedding.model");
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 4096})
    void outOfRangeEmbeddingDimensionIsRejected(int dimensions) {
        var props = valid();
        var candidate = new ExternalServicesProperties(
                props.contentCenter(), props.elasticsearch(), props.answerLlm(),
                new ExternalServicesProperties.QwenEmbedding(
                        props.qwenEmbedding().baseUrl(), "sk-embed", "text-embedding-v4", dimensions,
                        Duration.ofSeconds(30)));
        assertThat(violationsOf(candidate))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("qwenEmbedding.dimensions");
    }

    @Test
    @DisplayName("null sections are rejected so a missing KWIKI_* mapping fails at startup")
    void missingSectionsAreRejected() {
        assertThat(validator.validate(new ExternalServicesProperties(
                null, null, null, null)))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("contentCenter", "elasticsearch", "answerLlm", "qwenEmbedding");
    }
}
