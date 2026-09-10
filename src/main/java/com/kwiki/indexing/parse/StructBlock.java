package com.kwiki.indexing.parse;

/**
 * 一段结构化文本：标题携带其层级（1-6），其他一切
 * 都是段落（层级 0）。字符区间指向文档的纯文本。
 */
public record StructBlock(int headingLevel, String text, int charStart, int charEnd) {

    public boolean isHeading() {
        return headingLevel > 0;
    }
}
