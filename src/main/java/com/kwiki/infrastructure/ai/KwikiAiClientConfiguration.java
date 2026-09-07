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
 * AI provider adapter base. Creates low-level chat clients and an independent Qwen embedding
 * WebClient using kwiki-specific credentials. Clients are lazy: building them performs no network
 * call, so application startup never depends on provider availability.
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
     * Builds on Spring Boot's auto-configured {@link WebClient.Builder} so every provider call is
     * observed and automatically propagates the W3C traceparent of the in-flight request; the AI
     * clients never need manual trace headers.
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
