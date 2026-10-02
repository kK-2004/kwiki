package com.kwiki.indexing.search;
import com.kwiki.indexing.schema.KwikiSchemaV1;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.assertThat;
class MappingValidatorTest {
    private final KwikiSchemaV1 schema = new KwikiSchemaV1();
    @Test
    void 校验采用插件声明的必需字段与维度() {
        assertThat(MappingValidator.validate(schema.mapping(1024), 1024, schema.requiredFields())).isNull();
        assertThat(MappingValidator.validate(schema.mapping(1024), 2048, schema.requiredFields())).contains("vector dimension mismatch");
        var properties = new LinkedHashMap<>((Map<String,Object>)((Map<?,?>)schema.mapping(1024).get("mappings")).get("properties"));
        properties.remove("entityIds");
        assertThat(MappingValidator.validate(Map.of("mappings", Map.of("properties", properties)), 1024, schema.requiredFields())).isEqualTo("missing required field: entityIds");
    }
}
