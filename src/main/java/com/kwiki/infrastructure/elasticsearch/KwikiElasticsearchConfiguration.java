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
 * Elasticsearch 适配器（adapter）。传输客户端由
 * KWIKI_ELASTICSEARCH_URIS 自动配置（基础认证通过 KWIKI_ELASTICSEARCH_USERNAME/PASSWORD）；
 * 由于 Spring Boot 没有 api-key 属性，来自 KWIKI_ELASTICSEARCH_API_KEY 的
 * API key 在此处附加。仅配置一种认证（authentication）方式。
 */
@Configuration
public class KwikiElasticsearchConfiguration {

    /** 配置了 API key 时，附加 Authorization: ApiKey 请求头。 */
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
