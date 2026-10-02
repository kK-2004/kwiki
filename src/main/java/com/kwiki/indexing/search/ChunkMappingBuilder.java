package com.kwiki.indexing.search;
import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import org.springframework.stereotype.Component;
import java.util.Map;
/** 将字段格式映射与摘要委托给插件注册表。 */
@Component
public class ChunkMappingBuilder {
    private final ChunkIndexSchemaRegistry schemas;
    public ChunkMappingBuilder(ChunkIndexSchemaRegistry schemas) { this.schemas = schemas; }
    public Map<String, Object> buildMapping(int dimensions, String indexSchema) {
        return schemas.require(indexSchema).mapping(dimensions);
    }
    public String mappingHash(int dimensions, String indexSchema) {
        return schemas.mappingHash(indexSchema, dimensions);
    }
}
