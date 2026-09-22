package com.kwiki.infrastructure.elasticsearch;

import java.util.LinkedHashMap;
import java.util.Map;

/** COMMUNITY 索引的严格映射；不复用 Chunk parser 的 mapping。 */
public final class CommunityMappingBuilder {

    public Map<String, Object> build(int dimensions) {
        if (dimensions < 1) throw new IllegalArgumentException("社区向量维度无效");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("communityKey", keyword());
        properties.put("kbId", Map.of("type", "long"));
        properties.put("graphVersion", Map.of("type", "long"));
        properties.put("communityIndexVersion", Map.of("type", "long"));
        properties.put("communityId", keyword());
        properties.put("sourceManifestId", keyword());
        properties.put("sourceContentEpoch", Map.of("type", "long"));
        properties.put("sourceSecurityEpoch", Map.of("type", "long"));
        properties.put("sourceChunkIndexVersion", Map.of("type", "integer"));
        properties.put("title", Map.of("type", "text"));
        properties.put("summary", Map.of("type", "text"));
        properties.put("keywords", keyword());
        properties.put("representativeEntityIds", keyword());
        properties.put("sourceRefs", Map.of("type", "object", "dynamic", "strict",
                "properties", Map.of("sourceChunkId", keyword(),
                        "startOffset", Map.of("type", "integer"),
                        "endOffset", Map.of("type", "integer"))));
        properties.put("coreConcepts", keyword());
        properties.put("importantRelations", keyword());
        properties.put("questionTypes", keyword());
        properties.put("entityCount", Map.of("type", "integer"));
        properties.put("summaryModel", keyword());
        properties.put("summaryPromptVersion", keyword());
        properties.put("embeddingModel", keyword());
        properties.put("embeddingDimensions", Map.of("type", "integer"));
        properties.put("vector", Map.of("type", "dense_vector", "dims", dimensions,
                "index", true));
        return Map.of("mappings", Map.of("dynamic", "strict", "properties", properties));
    }

    private static Map<String, Object> keyword() {
        return Map.of("type", "keyword");
    }
}
