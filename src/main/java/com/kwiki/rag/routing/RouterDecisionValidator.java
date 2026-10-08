package com.kwiki.rag.routing;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对 LLM 路由输出做闭集 schema 校验。只接受
 * {intent, needsRetrieval, rewriteMode, subqueries, confidence} 这些字段，
 * 且取值必须为枚举值；拒绝未知字段、ES DSL、授权作用域、凭据以及
 * 图相关遗留内容。任何违规都会在上游回退到带作用域的 KNOWLEDGE_QA。
 */
public final class RouterDecisionValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "intent", "needsRetrieval", "rewriteMode", "subqueries", "confidence", "clarify");

    private static final Set<String> CLARIFY_FIELDS = Set.of("question", "options", "allowFreeText");

    private static final java.util.regex.Pattern OPTION_ID =
            java.util.regex.Pattern.compile("[a-z0-9_-]{1,40}");

    private static final java.util.regex.Pattern CREDENTIAL_LIKE = java.util.regex.Pattern.compile(
            "(sk-[A-Za-z0-9_-]{8,}|authorization|password\\s*[=:]|bearer\\s+\\S{8,})",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern ES_DSL_LIKE = java.util.regex.Pattern.compile(
            "(\\{\"query\"|\"bool\"\\s*:|\"term\"\\s*:|\"match\"\\s*:|\"_source\"|script_score)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private RouterDecisionValidator() {
    }

    public static RetrievalPlan validate(Map<String, Object> decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision is null");
        }
        for (String field : decision.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown field: " + field);
            }
        }
        if (!decision.containsKey("intent") || !decision.containsKey("needsRetrieval")
                || !decision.containsKey("rewriteMode")) {
            throw new IllegalArgumentException("missing required fields");
        }
        Intent intent = parseEnum(Intent.class, decision.get("intent"), "intent");
        RewriteMode rewriteMode = parseEnum(RewriteMode.class, decision.get("rewriteMode"),
                "rewriteMode");
        boolean needsRetrieval = booleanValue(decision.get("needsRetrieval"), "needsRetrieval");

        List<String> subqueries = subqueries(decision.get("subqueries"));
        double confidence = confidence(decision.get("confidence"));

        ClarifyRequest clarify = clarify(decision.get("clarify"));

        rejectDangerousContent(decision);
        RetrievalPlan plan = RetrievalPlan.llm(intent, needsRetrieval, rewriteMode, subqueries, confidence);
        return clarify == null ? plan : plan.withClarify(clarify);
    }

    private static ClarifyRequest clarify(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("clarify must be an object");
        }
        for (Object key : map.keySet()) {
            if (!CLARIFY_FIELDS.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("unknown clarify field: " + key);
            }
        }
        Object rawQuestion = map.get("question");
        if (!(rawQuestion instanceof String question) || question.isBlank() || question.length() > 200) {
            throw new IllegalArgumentException("clarify.question must be 1-200 chars");
        }
        List<ClarifyRequest.Option> options = new java.util.ArrayList<>();
        Object rawOptions = map.get("options");
        if (rawOptions != null) {
            if (!(rawOptions instanceof List<?> list)) {
                throw new IllegalArgumentException("clarify.options must be an array");
            }
            if (list.size() > ClarifyRequest.MAX_OPTIONS) {
                throw new IllegalArgumentException("too many clarify options");
            }
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> option) || option.size() != 2
                        || !(option.get("id") instanceof String id)
                        || !(option.get("label") instanceof String label)) {
                    throw new IllegalArgumentException("invalid clarify option");
                }
                if (!OPTION_ID.matcher(id).matches() || !ids.add(id)) {
                    throw new IllegalArgumentException("invalid clarify option id");
                }
                if (label.isBlank() || label.length() > 80) {
                    throw new IllegalArgumentException("invalid clarify option label");
                }
                options.add(new ClarifyRequest.Option(id, label.strip()));
            }
        }
        Object free = map.get("allowFreeText");
        boolean allowFreeText;
        if (free == null) {
            allowFreeText = true;
        } else if (free instanceof Boolean bool) {
            allowFreeText = bool;
        } else {
            throw new IllegalArgumentException("clarify.allowFreeText must be a boolean");
        }
        return new ClarifyRequest(question.strip(), options, allowFreeText, "router");
    }

    private static void rejectDangerousContent(Map<String, Object> decision) {
        String joined = String.valueOf(decision);
        if (ES_DSL_LIKE.matcher(joined).find()) {
            throw new IllegalArgumentException("ES DSL content rejected");
        }
        if (CREDENTIAL_LIKE.matcher(joined).find()) {
            throw new IllegalArgumentException("credential-like content rejected");
        }
        if (joined.contains("kbId") || joined.contains("scopeVersion")
                || joined.contains("authorizationScope")) {
            throw new IllegalArgumentException("authorization scope fields rejected");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, Object value, String field) {
        if (!(value instanceof String name)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported " + field + ": " + name);
        }
    }

    private static boolean booleanValue(Object value, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    private static List<String> subqueries(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("subqueries must be an array");
        }
        if (list.size() > 3) {
            throw new IllegalArgumentException("too many subqueries");
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("subquery must be nonblank");
                    }
                    return text.strip();
                })
                .toList();
    }

    private static double confidence(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            double conf = number.doubleValue();
            if (conf < 0.0 || conf > 1.0) {
                throw new IllegalArgumentException("confidence out of range");
            }
            return conf;
        }
        throw new IllegalArgumentException("confidence must be numeric");
    }
}
