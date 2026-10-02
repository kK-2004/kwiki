package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;

import java.util.Set;
import java.util.Map;

/**
 * 依据 kwiki 契约对已有索引映射做纯校验：
 * 向量维度必须与配置值一致，且每一个必需的
 * 溯源/作用域字段都必须存在。否则拒绝切换别名。
 * 必需字段集合由所选字段格式插件声明；旧物理索引保持可检索。
 */
public final class MappingValidator {

    private MappingValidator() {
    }

    /** 直接校验 Elasticsearch Java Client 的强类型响应，避免调试字符串/Jackson 结构漂移。 */
    public static String validate(TypeMapping mapping, int expectedDimensions,
                                  Set<String> requiredFields) {
        if (mapping == null) {
            return "missing mappings section";
        }
        if (mapping.dynamic() != DynamicMapping.Strict) {
            return "dynamic mapping must be strict";
        }
        Map<String, Property> properties = mapping.properties();
        if (properties == null || properties.isEmpty()) {
            return "missing properties section";
        }
        for (String field : requiredFields) {
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

    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> indexMapping, int expectedDimensions,
                                  Set<String> requiredFields) {
        Object mappingsNode = indexMapping.get("mappings");
        if (!(mappingsNode instanceof Map<?, ?> mappings)) {
            return "missing mappings section";
        }
        if (!"strict".equals(mappings.get("dynamic"))) {
            return "dynamic mapping must be strict";
        }
        Object propertiesNode = ((Map<String, Object>) mappings).get("properties");
        if (!(propertiesNode instanceof Map<?, ?> properties)) {
            return "missing properties section";
        }
        Map<String, Object> typed = (Map<String, Object>) properties;
        for (String field : requiredFields) {
            if (!typed.containsKey(field)) {
                return "missing required field: " + field;
            }
        }
        Object vector = typed.get("vector");
        if (vector instanceof Map<?, ?> vectorField) {
            if (!"dense_vector".equals(vectorField.get("type"))) {
                return "vector field is malformed";
            }
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
