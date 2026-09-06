package com.kwiki.indexing.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles structured blocks into a document, computing character offsets as
 * blocks are appended (blocks join with a blank line in the plain projection).
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
