package com.kwiki.graph;

/** 抽取结果中指向真实来源 Chunk 的位置引用。 */
public record GraphSourceRef(
        String sourceChunkId,
        int startOffset,
        int endOffset) {

    public GraphSourceRef {
        if (sourceChunkId == null || sourceChunkId.isBlank()) {
            throw new IllegalArgumentException("sourceChunkId 不能为空");
        }
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("来源位置无效");
        }
    }
}
