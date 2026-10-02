package com.kwiki.indexing.schema;

import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.search.ChunkDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 图文与实体关联字段格式，保留原结构 3 的映射和文档投影语义。 */
@Component
public class KwikiSchemaV1 implements ChunkIndexSchema {
    public static final String ID = "kwiki-schema-1";

    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "chunkLevel", "chunkKey", "parentChunkKey", "resourceType", "resourceId",
            "revisionId", "lifecycleVersion", "kbId", "headingPath", "charStart", "charEnd",
            "content", "contentIds", "sourceChunkId", "entityIds", "entityLinkingVersion",
            "entityLinkingStatus", "parserVersion", "chunkerVersion", "embeddingModel",
            "indexVersion", "vector");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "图文 + 实体关联 v1";
    }

    @Override
    public Map<String, Object> mapping(int embeddingDimensions) {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("chunkLevel", keyword());
        properties.put("chunkKey", keyword());
        properties.put("parentChunkKey", keyword());
        properties.put("resourceType", keyword());
        properties.put("resourceId", Map.of("type", "long"));
        properties.put("revisionId", Map.of("type", "long"));
        properties.put("lifecycleVersion", Map.of("type", "long"));
        properties.put("kbId", keyword());
        properties.put("parentOrdinal", Map.of("type", "integer"));
        properties.put("childOrdinal", Map.of("type", "integer"));
        properties.put("headingPath", keyword());
        properties.put("charStart", Map.of("type", "integer"));
        properties.put("charEnd", Map.of("type", "integer"));
        properties.put("content", Map.of("type", "text"));
        properties.put("contentIds", Map.of("type", "long"));
        properties.put("sourceChunkId", keyword());
        properties.put("entityIds", keyword());
        properties.put("entityLinkingVersion", keyword());
        properties.put("entityLinkingStatus", keyword());
        properties.put("parserVersion", keyword());
        properties.put("chunkerVersion", keyword());
        properties.put("embeddingModel", keyword());
        properties.put("indexVersion", Map.of("type", "integer"));
        properties.put("vector", Map.of(
                "type", "dense_vector",
                "dims", embeddingDimensions,
                "index", true));

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("dynamic", "strict");
        mapping.put("properties", properties);
        return Map.of("mappings", mapping);
    }

    @Override
    public Set<String> requiredFields() {
        return REQUIRED_FIELDS;
    }

    @Override
    public Set<IndexCapability> capabilities() {
        return Set.of(IndexCapability.RESOURCE_IDS, IndexCapability.ENTITY_LINKING);
    }

    @Override
    public void decorateParent(Map<String, Object> document, ParentChunk chunk, IndexedVersion version) {
        addContentIds(document, chunk.content());
    }

    @Override
    public void decorateChild(Map<String, Object> document, ChildChunk chunk, IndexedVersion version) {
        addContentIds(document, chunk.content());
        String entityLinkingVersion = version.entityLinkingVersion();
        String linkingVersion = entityLinkingVersion == null || entityLinkingVersion.isBlank()
                ? "entity-linking-v1" : entityLinkingVersion;
        document.put("sourceChunkId", sourceChunkId(chunk, version));
        document.put("entityIds", List.of());
        document.put("entityLinkingVersion", linkingVersion);
        document.put("entityLinkingStatus", "PENDING");
    }

    private static void addContentIds(Map<String, Object> document, String content) {
        List<Long> contentIds = ChunkDocument.resourceContentIds(content);
        if (!contentIds.isEmpty()) {
            document.put("contentIds", contentIds);
        }
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

    private static Map<String, Object> keyword() {
        return Map.of("type", "keyword");
    }
}
