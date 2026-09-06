package com.kwiki.testutil;

import java.util.List;

/**
 * Dummy property set shared by context-booting tests: satisfies binding validation
 * for every required kwiki.* value without contacting any external service. The
 * middleware auto-configurations are excluded so the default build stays Docker-free
 * (Redis support additionally re-enters through KwikiRedisConfiguration only when
 * kk.common.redis.enabled=true).
 */
public final class StandardTestProperties {

    /** Spring Redis auto-configurations stay out of every default test context. */
    public static final List<String> REDIS_AUTO_CONFIGURATIONS_EXCLUDED = List.of(
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");

    private static final String EXCLUDES = "spring.autoconfigure.exclude=" + String.join(",",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            String.join(",", REDIS_AUTO_CONFIGURATIONS_EXCLUDED),
            "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration",
            "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticsearchRestHealthContributorAutoConfiguration");

    public static final String[] VALUES = {
            "kwiki.security.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
            "kwiki.security.token-ttl=1h",
            "kwiki.content-center.base-url=http://localhost:8080",
            "kwiki.content-center.app-token=kapp-test-token",
            "kwiki.elasticsearch.uris=http://localhost:9200",
            "kwiki.answer-llm.base-url=http://localhost/v1",
            "kwiki.answer-llm.api-key=test",
            "kwiki.answer-llm.model=test-model",
            "kwiki.qwen-embedding.api-key=test",
            EXCLUDES,
    };

    private StandardTestProperties() {
    }

    /** Shared typed null-provider so tests stop re-declaring anonymous ObjectProviders. */
    public static <T> org.springframework.beans.factory.ObjectProvider<T> nullProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return null;
            }
        };
    }

    /**
     * Registers the dummy properties via @DynamicPropertySource so tests can share
     * one array (annotation attributes cannot reference non-constant arrays).
     */
    public static void register(org.springframework.test.context.DynamicPropertyRegistry registry) {
        for (String entry : VALUES) {
            int eq = entry.indexOf('=');
            String key = entry.substring(0, eq);
            String value = entry.substring(eq + 1);
            registry.add(key, () -> value);
        }
    }
}
