package com.kwiki.rag.answer;

import com.kwiki.rag.retrieval.ChunkHit;

import java.util.List;

/**
 * 生成上下文中的一个有界父级正文。截短可能缩短
 * 正文，但绝不移除已匹配子项的引用身份——子项仍保持
 * 挂载，即便其父级文本已被裁剪。
 */
public record ParentEvidence(
        String parentChunkKey,
        String resourceType,
        long resourceId,
        Long revisionId,
        long kbId,
        String headingPath,
        String body,
        boolean truncated,
        int firstHitOrder,
        List<ChunkHit> matchedChildren) {

    public ParentEvidence {
        matchedChildren = matchedChildren == null ? List.of() : List.copyOf(matchedChildren);
    }
}
