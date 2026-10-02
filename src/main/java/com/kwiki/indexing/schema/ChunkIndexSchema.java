package com.kwiki.indexing.schema;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.plugin.IndexPlugin;

import java.util.Map;
import java.util.Set;

/**
 * CHUNK 索引字段格式：决定物理索引映射、必需字段、文档特有字段与能力。
 * 修改索引字段须新建实现，经新建版本、迁移、切换上线；已用于版本的实现不得修改字段。
 */
public interface ChunkIndexSchema extends IndexPlugin {
    /** 完整 ES 映射，包含严格字段约束与 dense_vector。 */
    Map<String, Object> mapping(int embeddingDimensions);

    /** 校验物理索引时必须存在的字段。 */
    Set<String> requiredFields();

    /** 公共基础字段之外，父块的格式特有字段。 */
    void decorateParent(Map<String, Object> document, ParentChunk chunk, IndexedVersion version);

    /** 公共基础字段之外，子块的格式特有字段。 */
    void decorateChild(Map<String, Object> document, ChildChunk chunk, IndexedVersion version);

    /** 此字段格式提供的能力。 */
    Set<IndexCapability> capabilities();
}
