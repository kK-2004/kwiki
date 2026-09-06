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
 * Opt-in Redis support. Nothing here (or from Spring's Redis auto-configurations,
 * which are listed in spring.autoconfigure.exclude) loads until the deployment
 * explicitly sets kk.common.redis.enabled=true, so a disabled feature never reads
 * spring.data.redis.* nor creates a client. When enabled, the re-imported
 * auto-configurations provide the connection factory and the kk-common SDK contributes
 * its RedisTemplate/RedisUtil on top; this class additionally contributes a
 * readiness-only health indicator.
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
