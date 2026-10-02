package com.kwiki.indexing.schema;
import java.util.List;
import com.kwiki.indexing.config.IndexingDefaultsProperties;
/** 离线测试显式装配真实字段格式注册表。 */
public final class SchemaTestFixtures {
    private SchemaTestFixtures() { }
    public static ChunkIndexSchemaRegistry schemas() { return new ChunkIndexSchemaRegistry(List.of(new KwikiSchemaV1())); }
    public static IndexingDefaultsProperties defaults() { return new IndexingDefaultsProperties("kwiki-schema-1"); }
}
