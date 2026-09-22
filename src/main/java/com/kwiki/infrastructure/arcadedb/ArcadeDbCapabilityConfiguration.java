package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 暴露脱敏的 ArcadeDB 服务能力状态，不把凭据或响应正文返回给后台。 */
@Configuration
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbCapabilityConfiguration {

    @Bean
    ArcadeDbCapabilityProbe arcadeDbCapabilityProbe(ArcadeDbHttpAdapter client,
                                                    ArcadeDbProperties properties,
                                                    ObjectMapper objectMapper) {
        return new ArcadeDbCapabilityProbe(client, properties, objectMapper);
    }

    @Bean
    HealthIndicator arcadeDbHealthIndicator(ArcadeDbCapabilityProbe probe) {
        return () -> {
            var report = probe.probe();
            if (report.buildEnabled()) {
                return Health.up().withDetail("provider", "arcadedb")
                        .withDetail("serverVersion", report.serverVersion())
                        .withDetail("buildEnabled", true).build();
            }
            return Health.outOfService().withDetail("provider", "arcadedb")
                    .withDetail("serverVersion", report.serverVersion())
                    .withDetail("buildEnabled", false)
                    .withDetail("failureCode", report.failureCode()).build();
        };
    }
}
