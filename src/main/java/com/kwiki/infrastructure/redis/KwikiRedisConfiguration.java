package com.kwiki.infrastructure.redis;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 可选项（opt-in）的 Redis 支持。在部署方显式设置
 * kk.common.redis.enabled=true 之前，这里（以及 Spring 的 Redis 自动配置，
 * 已在 spring.autoconfigure.exclude 中列出）都不会加载，因此禁用状态下
 * 绝不会读取 spring.data.redis.*，也不会创建客户端。启用后，被重新引入的
 * 自动配置提供连接工厂，kk-common SDK 在其之上贡献
 * RedisTemplate/RedisUtil；本类另外贡献一个仅用于就绪状态（readiness）的健康指示器。
 */
@Configuration
@ConditionalOnProperty(prefix = "kk.common.redis", name = "enabled", havingValue = "true")
@Import({RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
public class KwikiRedisConfiguration {

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    HealthIndicator kwikiRedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return () -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                String pong = connection.ping();
                if ("PONG".equalsIgnoreCase(pong)) {
                    return Health.up().withDetail("provider", "redis").build();
                }
                return Health.outOfService()
                        .withDetail("provider", "redis")
                        .withDetail("error", "UnexpectedPingResponse")
                        .build();
            } catch (Exception e) {
                return Health.outOfService()
                        .withDetail("provider", "redis")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }
}
