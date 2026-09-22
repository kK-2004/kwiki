package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.KnowledgeGraphStore;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅在图开关开启时装配 schema 和图存储适配器。 */
@Configuration
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbGraphConfiguration {

    @Bean
    ArcadeDbSchemaInitializer arcadeDbSchemaInitializer(ArcadeDbHttpAdapter client,
                                                        ArcadeDbProperties properties) {
        return new ArcadeDbSchemaInitializer(client, properties);
    }

    @Bean
    KnowledgeGraphStore knowledgeGraphStore(ArcadeDbHttpAdapter client,
                                            ArcadeDbProperties properties,
                                            ObjectMapper objectMapper) {
        return new ArcadeDbKnowledgeGraphStore(client, properties, objectMapper);
    }
}
