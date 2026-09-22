package com.kwiki.indexing.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 带版本号分块索引的映射构建器。单一配置的向量嵌入
 * 维度驱动 dense_vector 字段，因此请求校验与映射
 * 永远不会彼此漂移。多模态映射（schema v2+）额外携带去重的
 * contentIds 数组；图增强映射（schema v3）再增加实体来源字段。
 */
@Component
public class ChunkMappingBuilder {

    /** 引入 contentIds 资源字段的映射结构代（多模态解析代）。 */
    public static final int MAPPING_SCHEMA_MULTIMODAL = 2;
    /** 引入 CHILD 实体映射字段的结构代。 */
    public static final int MAPPING_SCHEMA_ENTITY_LINKING = 3;

    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public Map<String, Object> buildMapping(int embeddingDimensions) {
        return buildMapping(embeddingDimensions, MAPPING_SCHEMA_MULTIMODAL);
    }

    /** 按物理索引配置构建严格 mapping；新字段只进入对应的新代索引。 */
    public Map<String, Object> buildMapping(int embeddingDimensions,
                                            int mappingSchemaVersion) {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("chunkLevel", keyword());
        properties.put("chunkKey", keyword());
        properties.put("parentChunkKey", keyword());
        properties.put("resourceType", keyword());
        properties.put("resourceId", Map.of("type", "long"));
        properties.put("revisionId", Map.of("type", "long"));
        properties.put("lifecycleVersion", Map.of("type", "long"));
        properties.put("kbId", keyword());
        properties.put("parentOrdinal", Map.of("type", "integer"));
        properties.put("childOrdinal", Map.of("type", "integer"));
        properties.put("headingPath", keyword());
        properties.put("charStart", Map.of("type", "integer"));
        properties.put("charEnd", Map.of("type", "integer"));
        properties.put("content", Map.of("type", "text"));
        if (mappingSchemaVersion >= MAPPING_SCHEMA_MULTIMODAL) {
            properties.put("contentIds", Map.of("type", "long"));
        }
        if (mappingSchemaVersion >= MAPPING_SCHEMA_ENTITY_LINKING) {
            properties.put("sourceChunkId", keyword());
            properties.put("entityIds", keyword());
            properties.put("entityLinkingVersion", keyword());
            properties.put("entityLinkingStatus", keyword());
        }
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

    /**
     * 映射定义的规范化哈希：key 排序 JSON 的 SHA-256。用于物理索引
     * 元数据与版本配置的一致性比对（ES 返回体含运行时细节，不能逐字节
     * 比较——比对永远基于本规范化形式）。
     */
    public String mappingHash(int embeddingDimensions) {
        return mappingHash(embeddingDimensions, MAPPING_SCHEMA_MULTIMODAL);
    }

    /** 版本化 mapping 的规范化哈希。 */
    public String mappingHash(int embeddingDimensions, int mappingSchemaVersion) {
        try {
            String canonical = canonicalMapper.writeValueAsString(
                    buildMapping(embeddingDimensions, mappingSchemaVersion));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("mapping hashing failed", failure);
        }
    }

    private static Map<String, Object> keyword() {
        return Map.of("type", "keyword");
    }
}
