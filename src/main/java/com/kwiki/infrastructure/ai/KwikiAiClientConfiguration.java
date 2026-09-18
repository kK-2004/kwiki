package com.kwiki.infrastructure.ai;

import com.kwiki.infrastructure.config.ExternalServicesProperties;

import com.fasterxml.jackson.databind.JsonNode;

import io.netty.channel.ChannelOption;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * AI 服务提供方适配器基类。创建底层对话客户端以及独立的千问（Qwen）向量嵌入
 * WebClient，并使用 kwiki 专属凭据。应用初始化时读取 provider 的模型元数据；
 * 若其公开输出上限，则把该上限用于回答请求，否则省略 max_tokens 并采用 provider 默认值。
 */
@Configuration
public class KwikiAiClientConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(KwikiAiClientConfiguration.class);

    private static final java.util.Set<String> OUTPUT_LIMIT_FIELDS = java.util.Set.of(
            "max_output_tokens", "maxOutputTokens",
            "max_completion_tokens", "maxCompletionTokens",
            "output_token_limit", "outputTokenLimit",
            "max_tokens", "maxTokens");

    @Bean
    AnswerModelCapabilities kwikiAnswerModelCapabilities(
            ExternalServicesProperties properties,
            WebClient.Builder webClientBuilder) {
        var provider = properties.answerLlm();
        WebClient client = webClientBuilder.clone()
                .baseUrl(provider.baseUrl().replaceAll("/+$", "") + "/")
                .defaultHeader("Authorization", "Bearer " + provider.apiKey())
                .build();
        Duration timeout = provider.requestTimeout().compareTo(Duration.ofSeconds(15)) < 0
                ? provider.requestTimeout() : Duration.ofSeconds(15);
        Throwable lastFailure = null;
        for (String path : java.util.List.of(
                "models/" + org.springframework.web.util.UriUtils.encodePathSegment(
                        provider.model(), java.nio.charset.StandardCharsets.UTF_8),
                "models")) {
            try {
                JsonNode document = client.get().uri(path).retrieve()
                        .bodyToMono(JsonNode.class).block(timeout);
                JsonNode model = selectModel(document, provider.model());
                Optional<DiscoveredLimit> discovered = findOutputLimit(model, "$");
                if (discovered.isPresent()) {
                    int limit = discovered.get().value();
                    log.info(
                            "answer model capabilities initialized model={} maxOutputTokens={} source=provider field={}",
                            provider.model(), limit, discovered.get().path());
                    return new AnswerModelCapabilities(
                            provider.model(), limit, "provider:" + discovered.get().path());
                }
            } catch (Exception failure) {
                lastFailure = failure;
                log.debug("answer model capability probe failed model={} endpoint={}: {}",
                        provider.model(), path,
                        com.kwiki.infrastructure.observability.SecretRedaction.redact(
                                failure.getMessage()));
            }
        }
        log.info(
                "answer model capabilities initialized model={} maxOutputTokens=provider-default source=metadata-unavailable",
                provider.model());
        if (lastFailure != null) {
            log.debug("answer model metadata was unavailable; requests will omit max tokens", lastFailure);
        }
        return new AnswerModelCapabilities(provider.model(), null, "provider-default");
    }

    private static JsonNode selectModel(JsonNode document, String modelName) {
        if (document != null && document.path("data").isArray()) {
            for (JsonNode candidate : document.path("data")) {
                if (modelName.equals(candidate.path("id").asText())) return candidate;
            }
        }
        return document;
    }

    static Optional<DiscoveredLimit> findOutputLimit(JsonNode node, String path) {
        if (node == null || node.isNull()) return Optional.empty();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (OUTPUT_LIMIT_FIELDS.contains(field.getKey())
                        && field.getValue().canConvertToInt()
                        && field.getValue().asInt() > 0) {
                    return Optional.of(new DiscoveredLimit(
                            field.getValue().asInt(), path + "." + field.getKey()));
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                Optional<DiscoveredLimit> nested =
                        findOutputLimit(field.getValue(), path + "." + field.getKey());
                if (nested.isPresent()) return nested;
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                Optional<DiscoveredLimit> nested = findOutputLimit(node.get(i), path + "[" + i + "]");
                if (nested.isPresent()) return nested;
            }
        }
        return Optional.empty();
    }

    record DiscoveredLimit(int value, String path) {
    }

    @Bean
    dev.langchain4j.model.chat.ChatModel kwikiChatModel(
            ExternalServicesProperties properties,
            java.util.Optional<io.micrometer.tracing.Tracer> tracer,
            java.util.Optional<io.micrometer.tracing.propagation.Propagator> propagator,
            AnswerModelCapabilities capabilities) {
        var p = properties.answerLlm();
        var builder = dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl(p.baseUrl())
                .apiKey(p.apiKey())
                .modelName(p.model())
                .temperature(0.0)
                .maxRetries(0)
                .timeout(p.requestTimeout())
                .httpClientBuilder(
                        new CancellableModelHttpClient.Builder()
                                .tracing(tracer.orElse(null), propagator.orElse(null)));
        if (capabilities.maxOutputTokens() != null) {
            builder.maxTokens(capabilities.maxOutputTokens());
        }
        return builder.build();
    }

    @Bean
    dev.langchain4j.model.chat.StreamingChatModel kwikiStreamingChatModel(
            ExternalServicesProperties properties,
            java.util.Optional<io.micrometer.tracing.Tracer> tracer,
            java.util.Optional<io.micrometer.tracing.propagation.Propagator> propagator,
            AnswerModelCapabilities capabilities) {
        var p = properties.answerLlm();
        var builder = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(p.baseUrl())
                .apiKey(p.apiKey())
                .modelName(p.model())
                .returnThinking(true)
                .strictJsonSchema(true)
                .temperature(0.2)
                .timeout(p.requestTimeout())
                .httpClientBuilder(
                        new CancellableModelHttpClient.Builder()
                                .tracing(tracer.orElse(null), propagator.orElse(null)));
        if (capabilities.maxOutputTokens() != null) {
            builder.maxTokens(capabilities.maxOutputTokens());
        }
        return builder.build();
    }

    @Bean
    WebClient kwikiEmbeddingWebClient(
            WebClient.Builder builder, ExternalServicesProperties properties) {
        ExternalServicesProperties.QwenEmbedding embedding = properties.qwenEmbedding();
        return buildClient(
                builder, embedding.baseUrl(), embedding.apiKey(), embedding.requestTimeout());
    }

    /**
     * 基于 Spring Boot 自动配置的 {@link WebClient.Builder} 构建，使每次
     * 提供商调用都可被观测，并自动传播在途请求的 W3C traceparent；AI
     * 客户端无需手动设置追踪头。
     */
    private WebClient buildClient(
            WebClient.Builder builder, String baseUrl, String apiKey, Duration responseTimeout) {
        HttpClient httpClient =
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(responseTimeout);
        return builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
