package com.kwiki.rag.routing;

import java.util.Locale;

/**
 * 确定性的查询归一化：去除首尾空白、合并连续空白、将
 * 拉丁字符转为小写，并保持 CJK 文本不变。原始查询永远不会被修改 ——
 * 归一化仅用于规则匹配与审计。
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
