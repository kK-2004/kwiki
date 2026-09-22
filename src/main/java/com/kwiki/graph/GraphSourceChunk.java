package com.kwiki.graph;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 图事实引用的稳定 Chunk 来源身份，不暴露任何 ArcadeDB 类型。 */
public record GraphSourceChunk(
        long kbId,
        String resourceType,
        long resourceId,
        Long revisionId,
        long lifecycleVersion,
        long indexVersion,
        String parserVersion,
        String chunkerVersion,
        String chunkKey,
        String contentHash) {

    public GraphSourceChunk {
        if (kbId <= 0 || resourceId <= 0 || lifecycleVersion < 0 || indexVersion < 0) {
            throw new IllegalArgumentException("图来源身份中的数值字段无效");
        }
        resourceType = requireText(resourceType, "resourceType");
        parserVersion = requireText(parserVersion, "parserVersion");
        chunkerVersion = requireText(chunkerVersion, "chunkerVersion");
        chunkKey = requireText(chunkKey, "chunkKey");
        contentHash = requireText(contentHash, "contentHash");
    }

    /**
     * 用完整来源元组生成稳定身份；长度前缀避免不同字段拼接后产生歧义。
     * 该身份不包含社区编号，因此重跑 Leiden 不会改变 Chunk 映射。
     */
    public String sourceChunkId() {
        String canonical = String.join("",
                framed("kbId", Long.toString(kbId)),
                framed("resourceType", resourceType),
                framed("resourceId", Long.toString(resourceId)),
                framed("revisionId", revisionId == null ? "<none>" : revisionId.toString()),
                framed("lifecycleVersion", Long.toString(lifecycleVersion)),
                framed("indexVersion", Long.toString(indexVersion)),
                framed("parserVersion", parserVersion),
                framed("chunkerVersion", chunkerVersion),
                framed("chunkKey", chunkKey),
                framed("contentHash", contentHash));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sc_");
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行时缺少 SHA-256", e);
        }
    }

    private static String framed(String name, String value) {
        return name.length() + ":" + name + value.length() + ":" + value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
