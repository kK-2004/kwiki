package com.kwiki.infrastructure.mysql;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * MySQL 适配器（adapter）。DataSource 本身由运维提供的
 * KWIKI_MYSQL_* 变量自动配置；本配置仅注册一个依赖相关的
 * 就绪状态（readiness）贡献器，使连接问题会阻断就绪状态但绝不影响存活状态。
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
