package com.kwiki.infrastructure.mysql;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * MySQL adapter. The DataSource itself is auto-configured from operator-provided
 * KWIKI_MYSQL_* variables; this configuration only registers a dependency-specific
 * readiness contributor so connectivity problems block readiness but never liveness.
 */
@Configuration
public class KwikiMySqlConfiguration {

    @Bean
    @ConditionalOnBean(JdbcOperations.class)
    HealthIndicator kwikiMysqlHealthIndicator(JdbcOperations jdbc) {
        return () -> {
            try {
                jdbc.queryForObject("SELECT 1", Integer.class);
                return Health.up().withDetail("provider", "mysql").build();
            } catch (Exception e) {
                return Health.outOfService()
                        .withDetail("provider", "mysql")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }
}
