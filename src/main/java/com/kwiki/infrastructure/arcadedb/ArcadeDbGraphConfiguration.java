package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.KnowledgeGraphStore;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅在图开关开启时装配 schema 和图存储适配器。 */
@Configuration
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbGraphConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ArcadeDbGraphConfiguration.class);

    @Bean
    ArcadeDbSchemaInitializer arcadeDbSchemaInitializer(ArcadeDbHttpAdapter client,
                                                        ArcadeDbProperties properties) {
        return new ArcadeDbSchemaInitializer(client, properties);
    }

    @Bean
    KnowledgeGraphStore knowledgeGraphStore(ArcadeDbHttpAdapter client,
                                            ArcadeDbProperties properties,
                                            ObjectMapper objectMapper,
                                            ArcadeDbSchemaInitializer schemaInitializer) {
        return new ArcadeDbKnowledgeGraphStore(client, properties, objectMapper, schemaInitializer);
    }

    /** 启动完成后尝试建库与初始化 schema；ArcadeDB 暂不可达只告警，首次写图时会重试。 */
    @Bean
    ApplicationListener<ApplicationReadyEvent> arcadeDbSchemaBootstrap(ArcadeDbSchemaInitializer initializer) {
        return event -> {
            try {
                var result = initializer.ensureInitialized();
                log.info("ArcadeDB schema 已就绪 schemaVersion={} commands={}",
                        result.schemaVersion(), result.commandCount());
            } catch (RuntimeException e) {
                log.warn("ArcadeDB schema 初始化失败，将在首次写图时重试 error={}", e.getMessage());
            }
        };
    }
}
