package com.kwiki.indexing.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * 把结构化块组装成文档，并在追加块时计算字符偏移
 * （块在纯文本投影中以空行连接）。受保护资源块与普通
 * 文本块走同一组装路径，保证偏移始终指向最终投影。
 */
public final class StructuredTextAssembler {

    private final List<StructBlock> blocks = new ArrayList<>();
    private final StringBuilder plain = new StringBuilder();

    public void append(int headingLevel, String text) {
        appendBlock(new StructBlock(headingLevel, text, -1, -1, null));
    }

    /** 追加一个受保护资源块（完整标记文本 + 资源身份）。 */
    public void appendProtectedResource(String blockText, long contentId) {
        appendBlock(new StructBlock(0, blockText, -1, -1, contentId));
    }

    private void appendBlock(StructBlock block) {
        String trimmed = block.text() == null ? "" : block.text().strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (plain.length() > 0) {
            plain.append("\n\n");
        }
        int start = plain.length();
        plain.append(trimmed);
        blocks.add(new StructBlock(block.headingLevel(), trimmed, start, plain.length(),
                block.contentId()));
    }

    public StructuredDocument build() {
        return new StructuredDocument(List.copyOf(blocks), plain.toString());
    }
}
