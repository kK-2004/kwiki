package com.kwiki.indexing.search;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.multimodal.ProtectedBlockProtocol;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.graph.GraphSourceChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 带版本号的 kwiki-chunks 索引的分块文档形态。文档 id 就是
 * 稳定的 chunk key，因此重放会确定性地覆盖。只有 CHILD
 * 文档携带稠密向量。content 是含受保护块的规范原文（资源
 * 字段的事实来源）；contentIds 由其中通过协议校验的受保护块
 * 重建——按首次出现去重，不含图片的分块不携带该字段，从而
 * 与 v1 严格映射（无 contentIds）的物理索引保持写兼容。
 */
public final class ChunkDocument {

    public static final String LEVEL_PARENT = "PARENT";
    public static final String LEVEL_CHILD = "CHILD";

    /** 投影解析用的宽裕协议实例：持久化摘要已在上游受配置上限约束。 */
    private static final ProtectedBlockProtocol RESOURCE_PROTOCOL =
            new ProtectedBlockProtocol(8192);

    private ChunkDocument() {
    }

    public static Map<String, Object> parent(ParentChunk chunk, IndexedVersion version) {
        Map<String, Object> document = commonFields(version);
        document.put("chunkLevel", LEVEL_PARENT);
        document.put("chunkKey", chunk.parentKey());
        document.put("parentChunkKey", null);
        document.put("parentOrdinal", chunk.parentOrdinal());
        document.put("headingPath", String.join(" > ", chunk.headingPath()));
        document.put("charStart", chunk.charStart());
        document.put("charEnd", chunk.charEnd());
        document.put("content", chunk.content());
        List<Long> contentIds = resourceContentIds(chunk.content());
        if (!contentIds.isEmpty()) {
            document.put("contentIds", contentIds);
        }
        return document;
    }

    public static Map<String, Object> child(ChildChunk chunk, float[] vector,
                                            IndexedVersion version) {
        Map<String, Object> document = baseChild(chunk, vector, version);
        if (version.mappingSchemaVersion() >= 3) {
            addPendingEntityFields(document, chunk, version, version.entityLinkingVersion());
        }
        return document;
    }

    /** 为支持图增强的物理版本生成带待回填实体字段的 CHILD。 */
    public static Map<String, Object> childWithEntityFields(ChildChunk chunk, float[] vector,
                                                              IndexedVersion version,
                                                              String entityLinkingVersion) {
        Map<String, Object> document = baseChild(chunk, vector, version);
        addPendingEntityFields(document, chunk, version, entityLinkingVersion);
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
        List<Long> contentIds = resourceContentIds(chunk.content());
        if (!contentIds.isEmpty()) {
            document.put("contentIds", contentIds);
        }
        document.put("vector", vector);
        return document;
    }

    private static void addPendingEntityFields(Map<String, Object> document, ChildChunk chunk,
                                               IndexedVersion version,
                                               String entityLinkingVersion) {
        String linkingVersion = entityLinkingVersion == null || entityLinkingVersion.isBlank()
                ? "entity-linking-v1" : entityLinkingVersion;
        document.put("sourceChunkId", sourceChunkId(chunk, version));
        document.put("entityIds", List.of());
        document.put("entityLinkingVersion", linkingVersion);
        document.put("entityLinkingStatus", "PENDING");
    }

    private static String sourceChunkId(ChildChunk chunk, IndexedVersion version) {
        return new GraphSourceChunk(
                version.kbId(), version.resourceType(), version.resourceId(), version.revisionId(),
                version.lifecycleVersion(), version.indexVersion(), version.parserVersion(),
                version.chunkerVersion(), chunk.childKey(), sha256(chunk.content())).sourceChunkId();
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行时缺少 SHA-256", failure);
        }
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
