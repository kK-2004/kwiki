package com.kwiki.indexing.parse;

/**
 * One block of structured text: headings carry their level (1-6), everything else
 * is a paragraph (level 0). Character ranges point into the document's plain text.
 */
public record StructBlock(int headingLevel, String text, int charStart, int charEnd) {

    public boolean isHeading() {
        return headingLevel > 0;
    }
}
