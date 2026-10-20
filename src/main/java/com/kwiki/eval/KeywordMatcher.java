package com.kwiki.eval;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 关键词匹配：文本与关键词统一做 NFKC、小写、去空白、去千分位逗号后做子串匹配。
 * 每个片段（chunk/上下文/回答）单独匹配，绝不跨片段拼接，避免边界处的假命中。
 */
public final class KeywordMatcher {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3})");

    private KeywordMatcher() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        normalized = WHITESPACE.matcher(normalized).replaceAll("");
        return THOUSANDS.matcher(normalized).replaceAll("");
    }

    /** 返回命中的关键词组下标（升序）。 */
    public static Set<Integer> matchedGroups(List<String> segments, List<List<String>> groups) {
        List<String> normalized = segments.stream().map(KeywordMatcher::normalize).toList();
        Set<Integer> matched = new TreeSet<>();
        for (int i = 0; i < groups.size(); i++) {
            for (String alias : groups.get(i)) {
                String target = normalize(alias);
                if (!target.isEmpty() && normalized.stream().anyMatch(text -> text.contains(target))) {
                    matched.add(i);
                    break;
                }
            }
        }
        return matched;
    }
}
