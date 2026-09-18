package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;

import java.util.List;
import java.util.Map;

/**
 * 依据 kwiki 契约对已有索引映射做纯校验：
 * 向量维度必须与配置值一致，且每一个必需的
 * 溯源/作用域字段都必须存在。否则拒绝切换别名。
 * mapping schema v2+（多模态）额外要求 contentIds
 * 资源字段；v1 物理索引保持 17 字段契约不变。
 */
public final class MappingValidator {

    private static final List<String> REQUIRED_FIELDS = List.of(
            "chunkLevel", "chunkKey", "parentChunkKey", "resourceType", "resourceId",
            "revisionId", "lifecycleVersion", "kbId", "headingPath", "charStart", "charEnd", "content",
            "parserVersion", "chunkerVersion", "embeddingModel", "indexVersion", "vector");

    private static final List<String> REQUIRED_FIELDS_V2 = List.of(
            "chunkLevel", "chunkKey", "parentChunkKey", "resourceType", "resourceId",
            "revisionId", "lifecycleVersion", "kbId", "headingPath", "charStart", "charEnd",
            "content", "contentIds", "parserVersion", "chunkerVersion", "embeddingModel",
            "indexVersion", "vector");

    private MappingValidator() {
    }

    /** v1 兼容入口（无 contentIds 要求）。 */
    public static String validate(TypeMapping mapping, int expectedDimensions) {
        return validate(mapping, expectedDimensions, 1);
    }

    /** 直接校验 Elasticsearch Java Client 的强类型响应，避免调试字符串/Jackson 结构漂移。 */
    public static String validate(TypeMapping mapping, int expectedDimensions,
                                  int mappingSchemaVersion) {
        if (mapping == null) {
            return "missing mappings section";
        }
        Map<String, Property> properties = mapping.properties();
        if (properties == null || properties.isEmpty()) {
            return "missing properties section";
        }
        for (String field : requiredFields(mappingSchemaVersion)) {
            if (!properties.containsKey(field)) {
                return "missing required field: " + field;
            }
        }
        Property vector = properties.get("vector");
        if (vector == null || !vector.isDenseVector()) {
            return "vector field is malformed";
        }
        var denseVector = vector.denseVector();
        if (denseVector.dims() == null || denseVector.dims() != expectedDimensions) {
            return "vector dimension mismatch: expected " + expectedDimensions;
        }
        if (!Boolean.TRUE.equals(denseVector.index())) {
            return "vector field is not indexed for kNN";
        }
        return null;
    }

    /** 返回错误描述；映射兼容时返回 null。 */
    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> indexMapping, int expectedDimensions) {
        return validate(indexMapping, expectedDimensions, 1);
    }

    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> indexMapping, int expectedDimensions,
                                  int mappingSchemaVersion) {
        Object mappingsNode = indexMapping.get("mappings");
        if (!(mappingsNode instanceof Map<?, ?> mappings)) {
            return "missing mappings section";
        }
        Object propertiesNode = ((Map<String, Object>) mappings).get("properties");
        if (!(propertiesNode instanceof Map<?, ?> properties)) {
            return "missing properties section";
        }
        Map<String, Object> typed = (Map<String, Object>) properties;
        for (String field : requiredFields(mappingSchemaVersion)) {
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

    private static List<String> requiredFields(int mappingSchemaVersion) {
        return mappingSchemaVersion >= 2 ? REQUIRED_FIELDS_V2 : REQUIRED_FIELDS;
    }
}
