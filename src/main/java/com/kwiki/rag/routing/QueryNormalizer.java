package com.kwiki.rag.routing;

import java.util.Locale;

/**
 * Deterministic query normalization: trims, collapses whitespace, lower-cases
 * Latin characters, and keeps CJK text intact. The raw query is never modified —
 * normalization exists only for rule matching and audits.
 */
public final class QueryNormalizer {

    private QueryNormalizer() {
    }

    public static String normalize(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        return rawQuery.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    public static boolean isBlank(String normalized) {
        return normalized == null || normalized.isBlank();
    }
}
