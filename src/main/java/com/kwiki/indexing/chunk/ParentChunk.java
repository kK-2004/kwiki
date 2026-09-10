package com.kwiki.indexing.chunk;

import java.util.List;

/**
 * 覆盖一个标题分区的父分块（或其一的合并/切分部分）。
 * charStart/charEnd 指向该版本的纯文本投影。
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
