package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import com.kwiki.indexing.schema.KwikiSchemaV1;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MappingValidatorTest {
    private final KwikiSchemaV1 schema = new KwikiSchemaV1();

    @Test
    void 校验采用插件声明的必需字段与维度() {
        assertThat(MappingValidator.validate(schema.mapping(1024), 1024, schema.requiredFields())).isNull();
        assertThat(MappingValidator.validate(schema.mapping(1024), 2048, schema.requiredFields())).contains("vector dimension mismatch");
        var properties = new LinkedHashMap<>(properties());
        properties.remove("entityIds");
        assertThat(MappingValidator.validate(Map.of("mappings", Map.of("dynamic", "strict", "properties", properties)),
                1024, schema.requiredFields())).isEqualTo("missing required field: entityIds");
    }

    @Test
    void 两个入口均拒绝非严格或缺失动态策略() {
        for (DynamicMapping dynamic : new DynamicMapping[]{DynamicMapping.True, DynamicMapping.False, null}) {
            assertThat(MappingValidator.validate(typed(dynamic, true), 1024, schema.requiredFields()))
                    .contains("dynamic mapping must be strict");
        }
        for (Object dynamic : new Object[]{true, false, "true", "false", null}) {
            var mappings = new LinkedHashMap<String, Object>();
            mappings.put("properties", properties());
            if (dynamic != null) mappings.put("dynamic", dynamic);
            assertThat(MappingValidator.validate(Map.of("mappings", mappings), 1024, schema.requiredFields()))
                    .contains("dynamic mapping must be strict");
        }
    }

    @Test
    void 两个入口均拒绝错误向量类型并接受完整严格映射() {
        assertThat(MappingValidator.validate(typed(DynamicMapping.Strict, true), 1024, schema.requiredFields())).isNull();
        assertThat(MappingValidator.validate(typed(DynamicMapping.Strict, false), 1024, schema.requiredFields()))
                .contains("vector field is malformed");
        for (String type : new String[]{"float", "keyword", ""}) {
            var properties = new LinkedHashMap<>(properties());
            properties.put("vector", Map.of("type", type, "dims", 1024, "index", true));
            assertThat(MappingValidator.validate(Map.of("mappings", Map.of("dynamic", "strict", "properties", properties)),
                    1024, schema.requiredFields())).contains("vector field is malformed");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties() {
        return (Map<String, Object>) ((Map<?, ?>) schema.mapping(1024).get("mappings")).get("properties");
    }

    private TypeMapping typed(DynamicMapping dynamic, boolean denseVector) {
        var mapping = new TypeMapping.Builder();
        if (dynamic != null) mapping.dynamic(dynamic);
        for (String field : schema.requiredFields()) {
            mapping.properties(field, Property.of(property -> property.keyword(keyword -> keyword)));
        }
        mapping.properties("vector", denseVector
                ? Property.of(property -> property.denseVector(vector -> vector.dims(1024).index(true)))
                : Property.of(property -> property.float_(value -> value)));
        return mapping.build();
    }
}
