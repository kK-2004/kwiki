package com.kwiki.infrastructure.observability;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 供 logback 转换器与请求过滤器共用的集中式凭据脱敏。
 * 保证授权头、API key、app token、密码以及
 * 已签名下载链接的查询参数绝不会出现在采集的日志中；这些值会被
 * 替换为 [REDACTED]，同时键与结构仍可供排查。
 */
public final class SecretRedaction {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[=:]\\s*)(bearer\\s+)?[^\\s,;\"]+");

    private static final Pattern KEYED_SECRET = Pattern.compile(
            "(?i)(\"?(?:x-)?(?:api[_-]?key|access[_-]?key|secret[_-]?key|password|passwd|"
                    + "client[_-]?secret|app[_-]?token|signature|sig|x-amz-signature|"
                    + "x-amz-credential|x-amz-security-token)\"?\\s*[=:]\\s*)(\"?[\\w./+%~=-]{4,}\"?)");

    /** 在预签名或登录 URL 中携带凭据的查询参数名（小写）。 */
    private static final Set<String> SENSITIVE_QUERY_PARAMS = Set.of(
            "signature", "sig", "x-amz-signature", "x-amz-credential", "x-amz-security-token",
            "access-key", "accesskey", "password", "passwd", "api-key", "apikey", "token",
            "secret", "secretkey");

    private SecretRedaction() {
    }

    /** 从自由格式的日志消息文本中脱敏掉凭据值。 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = AUTHORIZATION.matcher(text).replaceAll("$1$2" + REDACTED);
        result = KEYED_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        return result;
    }

    /** 从原始查询字符串中脱敏（redaction）敏感参数，同时保留顺序与名称。 */
    public static String redactQueryString(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return queryString;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (SENSITIVE_QUERY_PARAMS.contains(key)) {
                kept.add(rawKey + "=" + REDACTED);
            } else {
                kept.add(pair);
            }
        }
        return String.join("&", kept);
    }

    /** 当某个查询参数名被视为携带凭据时返回 true。 */
    public static boolean isSensitiveQueryParam(String rawKey) {
        String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return SENSITIVE_QUERY_PARAMS.contains(key);
    }

    static Matcher[] patternsForTest() {
        return new Matcher[]{AUTHORIZATION.matcher(""), KEYED_SECRET.matcher("")};
    }
}
