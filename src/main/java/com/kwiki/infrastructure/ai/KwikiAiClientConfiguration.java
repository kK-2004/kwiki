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
 * AI provider adapter base. Creates pre-configured WebClients for the answer LLM and
 * the Qwen embedding endpoint using kwiki-specific credentials. Clients are lazy:
 * building them performs no network call, so application startup never depends on
 * provider availability.
 */
@Configuration
public class KwikiAiClientConfiguration {

    @Bean
    WebClient kwikiAnswerLlmWebClient(WebClient.Builder builder, ExternalServicesProperties properties) {
        ExternalServicesProperties.AnswerLlm llm = properties.answerLlm();
        return buildClient(builder, llm.baseUrl(), llm.apiKey(), llm.requestTimeout());
    }

    @Bean
    WebClient kwikiEmbeddingWebClient(WebClient.Builder builder, ExternalServicesProperties properties) {
        ExternalServicesProperties.QwenEmbedding embedding = properties.qwenEmbedding();
        return buildClient(builder, embedding.baseUrl(), embedding.apiKey(), embedding.requestTimeout());
    }

    /**
     * Builds on Spring Boot's auto-configured {@link WebClient.Builder} so every
     * provider call is observed and automatically propagates the W3C traceparent
     * of the in-flight request; the AI clients never need manual trace headers.
     */
    private WebClient buildClient(WebClient.Builder builder, String baseUrl, String apiKey,
                                  Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(responseTimeout);
        return builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
