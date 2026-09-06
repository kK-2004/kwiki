package com.kwiki.indexing.parse;

import java.util.List;

/** Parser output: ordered blocks plus the plain-text projection they point into. */
public record StructuredDocument(List<StructBlock> blocks, String plainText) {
}
