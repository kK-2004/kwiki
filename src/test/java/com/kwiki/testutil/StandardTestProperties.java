package com.kwiki.testutil;

import java.util.List;

/**
 * 由需要启动上下文的测试共享的占位属性集：在完全不接触任何外部服务的前提下，
 * 满足每一个必需 kwiki.* 值的绑定校验。
 * 中间件自动配置被排除，因此默认构建保持无 Docker
 * （Redis 支持另外只有在
 * kk.common.redis.enabled=true 时才通过 KwikiRedisConfiguration 重新进入）。
 */
public final class StandardTestProperties {

    /** Spring Redis 自动配置不会进入任何默认测试上下文。 */
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

    /** 共享的类型化 null provider，免得测试反复声明匿名的 ObjectProvider。 */
    public static <T> org.springframework.beans.factory.ObjectProvider<T> nullProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return null;
            }
        };
    }

    /**
     * 通过 @DynamicPropertySource 注册这些占位属性，使测试可以共享
     * 同一个数组（注解属性无法引用非常量数组）。
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
