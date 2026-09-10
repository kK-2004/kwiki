package com.kwiki.infrastructure.contentcenter;

import com.kk.sdk.ContentCenterClient;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 从 kwiki.content-center 配置到单例 SDK 客户端的
 * Builder 映射，以及启动失败行为：空的 token 或 base URL 必须中止
 * 上下文启动，且不回显该密钥。
 */
class ContentCenterConfigurationTest {

    private static final String TOKEN = "kapp-unit-test-token";

    private static ExternalServicesProperties validProperties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "https://content-center.internal/", TOKEN, "kwiki", "attachments",
                        Duration.ofSeconds(7), Duration.ofSeconds(45)),
                new ExternalServicesProperties.Elasticsearch(null, "elastic", "secret"),
                new ExternalServicesProperties.AnswerLlm(
                        "https://llm.internal/v1", "sk-answer", "qwen-max", Duration.ofSeconds(120)),
                new ExternalServicesProperties.QwenEmbedding(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "sk-embed", "text-embedding-v4", 1024, Duration.ofSeconds(30)));
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    void clientIsBuiltFromConfiguredBaseUrlTokenAndTimeouts() throws Exception {
        ContentCenterClient client = new ContentCenterConfiguration()
                .kwikiContentCenterClient(validProperties());

        assertThat(field(client, "baseUrl")).isEqualTo("https://content-center.internal");
        assertThat(field(client, "appToken")).isEqualTo(TOKEN);
        assertThat(field(client, "connectTimeout")).isEqualTo(Duration.ofSeconds(7));
        assertThat(field(client, "requestTimeout")).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void blankTokenAbortsConstructionWithoutEchoingTheSecret() {
        String secret = "kapp-never-echo";
        ExternalServicesProperties properties = new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "https://content-center.internal", "  ", null, null,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                validProperties().elasticsearch(), validProperties().answerLlm(),
                validProperties().qwenEmbedding());

        assertThatThrownBy(() ->
                new ContentCenterConfiguration().kwikiContentCenterClient(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
    }

    @Test
    void blankBaseUrlAbortsConstruction() {
        ExternalServicesProperties properties = new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "   ", TOKEN, null, null, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                validProperties().elasticsearch(), validProperties().answerLlm(),
                validProperties().qwenEmbedding());

        assertThatThrownBy(() ->
                new ContentCenterConfiguration().kwikiContentCenterClient(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(TOKEN);
    }

    @Configuration
    @EnableConfigurationProperties(ExternalServicesProperties.class)
    static class BoundConfiguration {
        @Bean
        ContentCenterClient client(ExternalServicesProperties properties) {
            return new ContentCenterConfiguration().kwikiContentCenterClient(properties);
        }
    }

    @Test
    void springRegistersExactlyOneReusableClientSingleton() {
        new ApplicationContextRunner()
                .withUserConfiguration(BoundConfiguration.class)
                .withPropertyValues(
                        "kwiki.content-center.base-url=https://content-center.internal",
                        "kwiki.content-center.app-token=" + TOKEN,
                        "kwiki.content-center.connect-timeout=5s",
                        "kwiki.content-center.request-timeout=30s",
                        "kwiki.elasticsearch.username=elastic",
                        "kwiki.answer-llm.base-url=https://llm.internal/v1",
                        "kwiki.answer-llm.api-key=k",
                        "kwiki.answer-llm.model=m",
                        "kwiki.qwen-embedding.api-key=k")
                .run(context -> {
                    assertThat(context).hasSingleBean(ContentCenterClient.class);
                    assertThat(context.getBean(ContentCenterClient.class))
                            .isSameAs(context.getBean(ContentCenterClient.class));
                });
    }

    @Test
    void missingAppTokenFailsContextStartupWithoutEchoingTheSecret() {
        String secret = "kapp-context-startup-secret-not-used";
        new ApplicationContextRunner()
                .withUserConfiguration(BoundConfiguration.class)
                .withPropertyValues(
                        "kwiki.content-center.base-url=https://content-center.internal",
                        "kwiki.content-center.app-token=",
                        "kwiki.elasticsearch.username=elastic",
                        "kwiki.answer-llm.base-url=https://llm.internal/v1",
                        "kwiki.answer-llm.api-key=k",
                        "kwiki.answer-llm.model=m",
                        "kwiki.qwen-embedding.api-key=k")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageNotContaining(secret)
                            .hasMessageNotContaining("kapp-");
                });
    }
}
