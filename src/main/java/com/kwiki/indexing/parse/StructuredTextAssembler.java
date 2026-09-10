package com.kwiki.indexing.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * 把结构化块组装成文档，并在追加块时计算字符偏移
 * （块在纯文本投影中以空行连接）。
 */
public final class StructuredTextAssembler {

    private final List<StructBlock> blocks = new ArrayList<>();
    private final StringBuilder plain = new StringBuilder();

    public void append(int headingLevel, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (plain.length() > 0) {
            plain.append("\n\n");
        }
        int start = plain.length();
        plain.append(trimmed);
        blocks.add(new StructBlock(headingLevel, trimmed, start, plain.length()));
    }

    public StructuredDocument build() {
        return new StructuredDocument(List.copyOf(blocks), plain.toString());
    }
}
