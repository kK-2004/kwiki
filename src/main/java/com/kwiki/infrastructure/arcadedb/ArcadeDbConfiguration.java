package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅在图功能显式开启时装配 ArcadeDB 客户端，关闭时零外拨。 */
@Configuration
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbConfiguration {

    @Bean
    ArcadeDbHttpAdapter arcadeDbHttpAdapter(ArcadeDbProperties properties, ObjectMapper objectMapper) {
        return new ArcadeDbHttpAdapter(properties, objectMapper);
    }
}
