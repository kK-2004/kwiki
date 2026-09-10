package com.kwiki.indexing.search;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 映射契约：产出的映射与手写的夹具形态一致，
 * dense_vector 维度来自那个唯一配置值，且校验会在任何
 * 别名切换之前拒绝不兼容的维度或缺失的溯源字段。
 */
class ChunkMappingTest {

    private final ChunkMappingBuilder builder = new ChunkMappingBuilder();

    @Test
    void emittedMappingMatchesHandAuthoredFixtureShape() {
        Map<String, Object> mapping = builder.buildMapping(1024);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>)
                mapping.get("mappings")).get("properties");

        Map<String, Object> mappings = (Map<String, Object>) mapping.get("mappings");
        assertThat(mappings.get("dynamic")).isEqualTo("strict");

        assertThat(properties.get("chunkLevel")).isEqualTo(Map.of("type", "keyword"));
        assertThat(properties.get("content")).isEqualTo(Map.of("type", "text"));
        assertThat(properties.get("resourceId")).isEqualTo(Map.of("type", "long"));
        assertThat(properties.get("vector")).isEqualTo(Map.of(
                "type", "dense_vector", "dims", 1024, "index", true));
        for (String provenance : new String[]{"parserVersion", "chunkerVersion",
                "embeddingModel", "indexVersion"}) {
            assertThat(properties).containsKey(provenance);
        }
    }

    @Test
    void dimensionIsDrivenByConfiguration() {
        Map<String, Object> mapping = builder.buildMapping(256);
        @SuppressWarnings("unchecked")
        Map<String, Object> vector = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                mapping.get("mappings")).get("properties")).get("vector");
        assertThat(vector).isEqualTo(Map.of("type", "dense_vector", "dims", 256, "index", true));
    }

    @Test
    void compatibleMappingValidates() {
        assertThat(MappingValidator.validate(builder.buildMapping(1024), 1024)).isNull();
    }

    @Test
    void incompatibleDimensionsAreRejected() {
        String error = MappingValidator.validate(builder.buildMapping(1024), 768);
        assertThat(error).contains("dimension mismatch").contains("768");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingProvenanceFieldIsRejected() {
        Map<String, Object> mapping = builder.buildMapping(1024);
        Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>)
                mapping.get("mappings")).get("properties");
        Map<String, Object> trimmed = new HashMap<>(properties);
        trimmed.remove("parserVersion");

        String error = MappingValidator.validate(
                Map.of("mappings", Map.of("properties", trimmed)), 1024);
        assertThat(error).contains("parserVersion");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonIndexedVectorIsRejected() {
        Map<String, Object> mapping = new HashMap<>(builder.buildMapping(1024));
        Map<String, Object> mappings = new HashMap<>((Map<String, Object>) mapping.get("mappings"));
        Map<String, Object> properties = new HashMap<>(
                (Map<String, Object>) mappings.get("properties"));
        properties.put("vector", Map.of("type", "dense_vector", "dims", 1024, "index", false));
        mappings.put("properties", properties);
        mapping.put("mappings", mappings);

        assertThat(MappingValidator.validate(mapping, 1024)).contains("not indexed");
    }
}
