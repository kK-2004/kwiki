package com.kwiki.graph;

import com.kwiki.rag.retrieval.ChunkHit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 一个 CHILD 来源块：检索块内容加上还原稳定来源身份所需的文档字段。
 * sourceChunkId 在 CHILD 索引时由完整元组（含 lifecycleVersion）计算，
 * 因此列举结果必须携带这些字段，才能在构建侧还原出同一个稳定身份。
 */
public record GraphSourceChild(ChunkHit hit, long lifecycleVersion,
                               String parserVersion, String chunkerVersion) {

    public GraphSourceChild {
        if (hit == null || parserVersion == null || parserVersion.isBlank()
                || chunkerVersion == null || chunkerVersion.isBlank() || lifecycleVersion < 0) {
            throw new IllegalArgumentException("来源子块身份无效");
        }
    }

    /** 还原为与 CHILD 文档一致的稳定来源身份；contentHash 重新按正文计算。 */
    public GraphSourceChunk toSourceChunk(long indexVersion) {
        return new GraphSourceChunk(hit.kbId(), hit.resourceType(), hit.resourceId(),
                hit.revisionId(), lifecycleVersion, indexVersion, parserVersion,
                chunkerVersion, hit.chunkKey(), sha256(hit.content()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("运行时缺少 SHA-256", failure);
        }
    }
}
