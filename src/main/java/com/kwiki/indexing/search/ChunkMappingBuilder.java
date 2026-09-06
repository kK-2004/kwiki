package com.kwiki.indexing.search;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mapping builder for the versioned chunk index. The single configured embedding
 * dimension drives the dense_vector field so request validation and the mapping
 * can never drift apart.
 */
@Component
public class ChunkMappingBuilder {

    public Map<String, Object> buildMapping(int embeddingDimensions) {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("chunkLevel", keyword());
        properties.put("chunkKey", keyword());
        properties.put("parentChunkKey", keyword());
        properties.put("resourceType", keyword());
        properties.put("resourceId", Map.of("type", "long"));
        properties.put("revisionId", Map.of("type", "long"));
        properties.put("kbId", keyword());
        properties.put("parentOrdinal", Map.of("type", "integer"));
        properties.put("childOrdinal", Map.of("type", "integer"));
        properties.put("headingPath", keyword());
        properties.put("charStart", Map.of("type", "integer"));
        properties.put("charEnd", Map.of("type", "integer"));
        properties.put("content", Map.of("type", "text"));
        properties.put("parserVersion", keyword());
        properties.put("chunkerVersion", keyword());
        properties.put("embeddingModel", keyword());
        properties.put("indexVersion", Map.of("type", "integer"));
        properties.put("vector", Map.of(
                "type", "dense_vector",
                "dims", embeddingDimensions,
                "index", true));

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("dynamic", "strict");
        mapping.put("properties", properties);
        return Map.of("mappings", mapping);
    }

    private static Map<String, Object> keyword() {
        return Map.of("type", "keyword");
    }
}
