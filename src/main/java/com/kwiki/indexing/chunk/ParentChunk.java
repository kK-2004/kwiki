package com.kwiki.indexing.chunk;

import java.util.List;

/**
 * A parent chunk covering one heading section (or a merged/split part of one).
 * charStart/charEnd point into the revision's plain-text projection.
 */
public record ParentChunk(
        String parentKey,
        int parentOrdinal,
        List<String> headingPath,
        int charStart,
        int charEnd,
        String content,
        BoundaryType boundaryType) {

    public enum BoundaryType {HEADING, PARAGRAPH, HARD}
}
