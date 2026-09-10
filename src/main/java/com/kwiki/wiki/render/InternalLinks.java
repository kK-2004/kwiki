package com.kwiki.wiki.render;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Markdown 中提取稳定的页面引用。内部链接使用 kwiki-page: 方案
 * （[title](kwiki-page:{uuid})），因此重命名与移动都不会破坏链接——
 * 解析走页面 uuid 而非标题。
 */
public final class InternalLinks {

    private static final Pattern PAGE_LINK = Pattern.compile("kwiki-page:([0-9a-fA-F-]{36})");

    private InternalLinks() {
    }

    /** 内部链接引用的页面 uuid，按首次出现顺序去重。 */
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
