package com.kwiki.indexing.search;

import com.kwiki.indexing.schema.ChunkIndexSchema;
import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.multimodal.ProtectedBlockProtocol;
import com.kwiki.indexing.pipeline.IndexedVersion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本化分块索引的公共文档字段。文档 id 使用稳定 chunk key；
 * 只有 CHILD 携带向量，资源与实体字段由所选字段格式插件追加。
 */
public final class ChunkDocument {

    public static final String LEVEL_PARENT = "PARENT";
    public static final String LEVEL_CHILD = "CHILD";

    /** 投影解析用的宽裕协议实例：持久化摘要已在上游受配置上限约束。 */
    private static final ProtectedBlockProtocol RESOURCE_PROTOCOL =
            new ProtectedBlockProtocol(8192);

    private ChunkDocument() {
    }

    public static Map<String, Object> parent(ParentChunk chunk, IndexedVersion version, ChunkIndexSchema schema) {
        Map<String, Object> document = commonFields(version);
        document.put("chunkLevel", LEVEL_PARENT);
        document.put("chunkKey", chunk.parentKey());
        document.put("parentChunkKey", null);
        document.put("parentOrdinal", chunk.parentOrdinal());
        document.put("headingPath", String.join(" > ", chunk.headingPath()));
        document.put("charStart", chunk.charStart());
        document.put("charEnd", chunk.charEnd());
        document.put("content", chunk.content());
        schema.decorateParent(document, chunk, version);
        return document;
    }

    public static Map<String, Object> child(ChildChunk chunk, float[] vector,
                                           IndexedVersion version, ChunkIndexSchema schema) {
        Map<String, Object> document = baseChild(chunk, vector, version);
        schema.decorateChild(document, chunk, version);
        return document;
    }

    private static Map<String, Object> baseChild(ChildChunk chunk, float[] vector,
                                                  IndexedVersion version) {
        Map<String, Object> document = commonFields(version);
        document.put("chunkLevel", LEVEL_CHILD);
        document.put("chunkKey", chunk.childKey());
        document.put("parentChunkKey", chunk.parentKey());
        document.put("childOrdinal", chunk.childOrdinal());
        document.put("charStart", chunk.charStart());
        document.put("charEnd", chunk.charEnd());
        document.put("content", chunk.content());
        document.put("vector", vector);
        return document;
    }

    /** 由规范原文中的受保护块重建去重 contentIds（首现顺序）。 */
    public static List<Long> resourceContentIds(String chunkContent) {
        if (chunkContent == null
                || !chunkContent.contains(ProtectedBlockProtocol.START_PREFIX)) {
            return List.of();
        }
        return RESOURCE_PROTOCOL.resourceRefsDistinct(chunkContent).stream()
                .map(ProtectedBlockProtocol.ResourceRef::contentId)
                .toList();
    }

    private static Map<String, Object> commonFields(IndexedVersion version) {
        Map<String, Object> document = new HashMap<>();
        document.put("resourceType", version.resourceType());
        document.put("resourceId", version.resourceId());
        document.put("revisionId", version.revisionId());
        document.put("lifecycleVersion", version.lifecycleVersion());
        document.put("kbId", version.kbId());
        document.put("parserVersion", version.parserVersion());
        document.put("chunkerVersion", version.chunkerVersion());
        document.put("embeddingModel", version.embeddingModel());
        document.put("indexVersion", version.indexVersion());
        return document;
    }
}
