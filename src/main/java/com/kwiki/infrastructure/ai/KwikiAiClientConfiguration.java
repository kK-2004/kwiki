package com.kwiki.infrastructure.ai;

import com.kwiki.infrastructure.config.ExternalServicesProperties;

import io.netty.channel.ChannelOption;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * AI 服务提供方适配器基类。创建底层对话客户端以及独立的千问（Qwen）向量嵌入
 * WebClient，并使用 kwiki 专属凭据。客户端为懒加载：构建它们不会发起任何网络
 * 调用，因此应用启动从不依赖服务提供方是否可用。
 */
@Configuration
public class KwikiAiClientConfiguration {

    @Bean
    dev.langchain4j.model.chat.ChatModel kwikiChatModel(
            ExternalServicesProperties properties,
            java.util.Optional<io.micrometer.tracing.Tracer> tracer,
            java.util.Optional<io.micrometer.tracing.propagation.Propagator> propagator) {
        var p = properties.answerLlm();
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl(p.baseUrl())
                .apiKey(p.apiKey())
                .modelName(p.model())
                .temperature(0.0)
                .maxTokens(2048)
                .maxRetries(0)
                .timeout(p.requestTimeout())
                .httpClientBuilder(
                        new CancellableModelHttpClient.Builder()
                                .tracing(tracer.orElse(null), propagator.orElse(null)))
                .build();
    }

    @Bean
    dev.langchain4j.model.chat.StreamingChatModel kwikiStreamingChatModel(
            ExternalServicesProperties properties,
            java.util.Optional<io.micrometer.tracing.Tracer> tracer,
            java.util.Optional<io.micrometer.tracing.propagation.Propagator> propagator) {
        var p = properties.answerLlm();
        return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(p.baseUrl())
                .apiKey(p.apiKey())
                .modelName(p.model())
                .temperature(0.2)
                .maxTokens(4096)
                .timeout(p.requestTimeout())
                .httpClientBuilder(
                        new CancellableModelHttpClient.Builder()
                                .tracing(tracer.orElse(null), propagator.orElse(null)))
                .build();
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
