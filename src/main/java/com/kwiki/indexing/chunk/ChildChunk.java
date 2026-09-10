package com.kwiki.indexing.chunk;

/** 恰好归属一个父分块的子分块；是唯一承载向量的层级。 */
public record ChildChunk(
        String childKey,
        int childOrdinal,
        String parentKey,
        int charStart,
        int charEnd,
        String content,
        BoundaryType boundaryType) {

    public enum BoundaryType {PARAGRAPH, SENTENCE, HARD}
}
