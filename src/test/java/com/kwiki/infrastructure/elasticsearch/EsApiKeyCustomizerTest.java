package com.kwiki.infrastructure.elasticsearch;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.elasticsearch.client.RestClientBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ES auth wiring: an API key becomes exactly one Authorization: ApiKey header;
 * a blank key leaves the builder untouched (basic auth path stays with Spring
 * Boot's username/password properties).
 */
@ExtendWith(MockitoExtension.class)
class EsApiKeyCustomizerTest {

    @Mock
    RestClientBuilder builder;

    private static ExternalServicesProperties properties(String apiKey) {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc:8080", "kapp-test",
                        null, null, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(
                        apiKey, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m",
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k",
                        "text-embedding-v4", 1024, Duration.ofSeconds(10)));
    }

    @Test
    void apiKeyIsAttachedAsAuthorizationHeader() {
        new KwikiElasticsearchConfiguration()
                .kwikiElasticsearchApiKeyCustomizer(properties("es-key-123"))
                .customize(builder);

        verify(builder).setDefaultHeaders(argThat(headers ->
                headers != null && headers.length == 1
                        && "Authorization".equals(headers[0].getName())
                        && "ApiKey es-key-123".equals(headers[0].getValue())));
    }

    @Test
    void blankApiKeyLeavesBuilderUntouched() {
        new KwikiElasticsearchConfiguration()
                .kwikiElasticsearchApiKeyCustomizer(properties("  "))
                .customize(builder);

        verify(builder, never()).setDefaultHeaders(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingApiKeyLeavesBuilderUntouched() {
        new KwikiElasticsearchConfiguration()
                .kwikiElasticsearchApiKeyCustomizer(properties(null))
                .customize(builder);

        verify(builder, never()).setDefaultHeaders(org.mockito.ArgumentMatchers.any());
    }
}
