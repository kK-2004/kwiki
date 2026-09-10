package com.kwiki.indexing.parse;

import java.util.List;

/** 解析器输出：有序的块，以及它们所指向的纯文本投影。 */
public record StructuredDocument(List<StructBlock> blocks, String plainText) {
}
