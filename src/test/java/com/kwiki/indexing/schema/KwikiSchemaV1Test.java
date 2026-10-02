package com.kwiki.indexing.schema;

import com.kwiki.indexing.config.IndexingDefaultsProperties;
import com.kwiki.indexing.config.IndexingDefaultsValidator;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class KwikiSchemaV1Test {
    private final KwikiSchemaV1 schema = new KwikiSchemaV1();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchemaConfiguration.class);

    @Test
    void 映射摘要与原结构3完全一致() {
        assertThat(ChunkIndexSchemaRegistry.mappingHash(schema.mapping(1024)))
                .isEqualTo("230402a7da44cfefc07f82e005d90e53d54f5fab557552c53add12a6c9de09c2");
        var original = new ChunkMappingBuilder();
        for (int dimensions : List.of(64, 1024, 2048)) {
            assertThat(schema.mapping(dimensions)).isEqualTo(original.buildMapping(dimensions, 3));
            assertThat(properties(schema.mapping(dimensions)).keySet())
                    .containsExactlyElementsOf(properties(original.buildMapping(dimensions, 3)).keySet());
            assertThat(ChunkIndexSchemaRegistry.mappingHash(schema.mapping(dimensions)))
                    .isEqualTo(original.mappingHash(dimensions, 3));
        }
    }

    @Test
    void 必需字段与映射字段一致且提供两项能力() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) schema.mapping(1024)
                .get("mappings")).get("properties");
        assertThat(props.keySet()).containsAll(schema.requiredFields());
        assertThat(schema.requiredFields()).contains("contentIds", "sourceChunkId", "entityIds",
                "entityLinkingVersion", "entityLinkingStatus", "vector");
        assertThat(schema.capabilities()).containsExactlyInAnyOrder(
                IndexCapability.RESOURCE_IDS, IndexCapability.ENTITY_LINKING);
        assertThat(schema.id()).isEqualTo("kwiki-schema-1");
        assertThat(schema.label()).isEqualTo("图文 + 实体关联 v1");
    }

    @Test
    void 注册表拒绝未注册的默认字段格式() {
        var registry = new ChunkIndexSchemaRegistry(List.of(schema));
        assertThat(registry.require("kwiki-schema-1")).isSameAs(schema);
        assertThat(registry.mappingHash(schema.id(), 1024))
                .isEqualTo(ChunkIndexSchemaRegistry.mappingHash(schema.mapping(1024)));
        assertThatThrownBy(() -> registry.require("legacy-schema-2"))
                .hasMessageContaining("legacy-schema-2");
    }

    @Test
    void 未配置时绑定默认字段格式且启动成功() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IndexingDefaultsProperties.class).indexSchema())
                    .isEqualTo(KwikiSchemaV1.ID);
        });
    }

    @Test
    void 未注册的默认字段格式阻止启动() {
        contextRunner.withPropertyValues("kwiki.indexing.defaults.index-schema=legacy-schema-2")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("默认字段格式未注册：legacy-schema-2"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> mapping) {
        return (Map<String, Object>) ((Map<String, Object>) mapping.get("mappings")).get("properties");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IndexingDefaultsProperties.class)
    @Import({KwikiSchemaV1.class, ChunkIndexSchemaRegistry.class, IndexingDefaultsValidator.class})
    static class SchemaConfiguration {
    }
}
