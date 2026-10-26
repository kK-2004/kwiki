package com.kwiki.graph;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 实体名称归一化：全角转半角（NFKC）、去首尾空白、合并连续空白、转小写。 */
public final class GraphEntityNames {

    private GraphEntityNames() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 名称与全部别名归一化后的去重集合（空串剔除），名称在前。 */
    public static Set<String> terms(String name, List<String> aliases) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = normalize(name);
        if (!normalized.isEmpty()) {
            terms.add(normalized);
        }
        if (aliases != null) {
            for (String alias : aliases) {
                String value = normalize(alias);
                if (!value.isEmpty()) {
                    terms.add(value);
                }
            }
        }
        return terms;
    }
}
