package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.elasticsearch.RestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch adapter. The transport client is auto-configured from
 * KWIKI_ELASTICSEARCH_URIS (basic auth via KWIKI_ELASTICSEARCH_USERNAME/PASSWORD);
 * an API key from KWIKI_ELASTICSEARCH_API_KEY is attached here because Spring
 * Boot has no api-key property. Configure only one auth style.
 */
@Configuration
public class KwikiElasticsearchConfiguration {

    /** Attaches the Authorization: ApiKey header when an API key is configured. */
    @Bean
    RestClientBuilderCustomizer kwikiElasticsearchApiKeyCustomizer(
            ExternalServicesProperties properties) {
        return builder -> {
            String apiKey = properties.elasticsearch().apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.setDefaultHeaders(new Header[]{
                        new BasicHeader("Authorization", "ApiKey " + apiKey)});
            }
        };
    }

    @Bean
    @ConditionalOnBean(ElasticsearchClient.class)
    HealthIndicator kwikiElasticsearchHealthIndicator(ElasticsearchClient client) {
        return () -> {
            try {
                if (client.ping().value()) {
                    return Health.up().withDetail("provider", "elasticsearch").build();
                }
                return Health.outOfService()
                        .withDetail("provider", "elasticsearch")
                        .withDetail("error", "PingReturnedFalse")
                        .build();
            } catch (Exception e) {
                return Health.outOfService()
                        .withDetail("provider", "elasticsearch")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }
}
