package com.kwiki.indexing.parse;

/**
 * 一段结构化文本：标题携带其层级（1-6），其他一切
 * 都是段落（层级 0）。字符区间指向文档的纯文本。
 * 受保护资源块（图片）同样是层级 0 的块，其文本为完整
 * KWIKI_META_DATA 标记协议块，contentId 记录去重后的
 * 资源身份——它是分块原子性与 ES resource 字段的事实来源，
 * 绝不从自由文本扫描得出。
 */
public record StructBlock(int headingLevel, String text, int charStart, int charEnd, Long contentId) {

    public StructBlock(int headingLevel, String text, int charStart, int charEnd) {
        this(headingLevel, text, charStart, charEnd, null);
    }

    public boolean isHeading() {
        return headingLevel > 0;
    }

    /** 受保护资源块：整个块（含标记与摘要）不可被切分。 */
    public boolean isProtectedResource() {
        return contentId != null && contentId > 0;
    }
}
