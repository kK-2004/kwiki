package com.kwiki.indexing.chunk;

/** A child chunk within exactly one parent; the only level carrying vectors. */
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
