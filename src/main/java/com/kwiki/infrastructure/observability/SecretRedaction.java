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
 * Central credential redaction used by the logback converter and the request filter.
 * Guarantees that authorization headers, API keys, app tokens, passwords, and
 * signed download-link query parameters never appear in captured logs; values are
 * replaced with [REDACTED] while keys and structure stay diagnosable.
 */
public final class SecretRedaction {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[=:]\\s*)(bearer\\s+)?[^\\s,;\"]+");

    private static final Pattern KEYED_SECRET = Pattern.compile(
            "(?i)(\"?(?:x-)?(?:api[_-]?key|access[_-]?key|secret[_-]?key|password|passwd|"
                    + "client[_-]?secret|app[_-]?token|signature|sig|x-amz-signature|"
                    + "x-amz-credential|x-amz-security-token)\"?\\s*[=:]\\s*)(\"?[\\w./+%~=-]{4,}\"?)");

    /** Query parameter names (lower-case) that carry credentials in presigned or login URLs. */
    private static final Set<String> SENSITIVE_QUERY_PARAMS = Set.of(
            "signature", "sig", "x-amz-signature", "x-amz-credential", "x-amz-security-token",
            "access-key", "accesskey", "password", "passwd", "api-key", "apikey", "token",
            "secret", "secretkey");

    private SecretRedaction() {
    }

    /** Redacts credential values from free-form log message text. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = AUTHORIZATION.matcher(text).replaceAll("$1$2" + REDACTED);
        result = KEYED_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        return result;
    }

    /** Redacts sensitive parameters from a raw query string while preserving order and names. */
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

    /** True when a query parameter name is treated as credential-carrying. */
    public static boolean isSensitiveQueryParam(String rawKey) {
        String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return SENSITIVE_QUERY_PARAMS.contains(key);
    }

    static Matcher[] patternsForTest() {
        return new Matcher[]{AUTHORIZATION.matcher(""), KEYED_SECRET.matcher("")};
    }
}
