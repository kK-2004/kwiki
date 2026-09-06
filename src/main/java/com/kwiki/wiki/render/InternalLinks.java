package com.kwiki.wiki.render;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts stable page references from Markdown. Internal links use the
 * kwiki-page: scheme ([title](kwiki-page:{uuid})), so renames and moves never
 * break links — resolution goes through the page uuid, not the title.
 */
public final class InternalLinks {

    private static final Pattern PAGE_LINK = Pattern.compile("kwiki-page:([0-9a-fA-F-]{36})");

    private InternalLinks() {
    }

    /** Page uuids referenced by internal links, in first-occurrence order, deduplicated. */
    public static Set<String> extractPageUuids(String markdown) {
        Set<String> uuids = new LinkedHashSet<>();
        if (markdown == null) {
            return uuids;
        }
        Matcher matcher = PAGE_LINK.matcher(markdown);
        while (matcher.find()) {
            uuids.add(matcher.group(1));
        }
        return uuids;
    }
}
