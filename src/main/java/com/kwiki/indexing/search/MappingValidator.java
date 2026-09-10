package com.kwiki.indexing.search;

import java.util.List;
import java.util.Map;

/**
 * 依据 kwiki 契约对已有索引映射做纯校验：
 * 向量维度必须与配置值一致，且每一个必需的
 * 溯源/作用域字段都必须存在。否则拒绝切换别名。
 */
public final class MappingValidator {

    private static final List<String> REQUIRED_FIELDS = List.of(
            "chunkLevel", "chunkKey", "parentChunkKey", "resourceType", "resourceId",
            "revisionId", "kbId", "headingPath", "charStart", "charEnd", "content",
            "parserVersion", "chunkerVersion", "embeddingModel", "indexVersion", "vector");

    private MappingValidator() {
    }

    /** 返回错误描述；映射兼容时返回 null。 */
    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> indexMapping, int expectedDimensions) {
        Object mappingsNode = indexMapping.get("mappings");
        if (!(mappingsNode instanceof Map<?, ?> mappings)) {
            return "missing mappings section";
        }
        Object propertiesNode = ((Map<String, Object>) mappings).get("properties");
        if (!(propertiesNode instanceof Map<?, ?> properties)) {
            return "missing properties section";
        }
        Map<String, Object> typed = (Map<String, Object>) properties;
        for (String field : REQUIRED_FIELDS) {
            if (!typed.containsKey(field)) {
                return "missing required field: " + field;
            }
        }
        Object vector = typed.get("vector");
        if (vector instanceof Map<?, ?> vectorField) {
            Object dims = vectorField.get("dims");
            if (!(dims instanceof Number number) || number.intValue() != expectedDimensions) {
                return "vector dimension mismatch: expected " + expectedDimensions;
            }
            if (!Boolean.TRUE.equals(vectorField.get("index"))) {
                return "vector field is not indexed for kNN";
            }
            return null;
        }
        return "vector field is malformed";
    }
}
