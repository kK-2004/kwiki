package com.kwiki.infrastructure.ai.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 模型与用途分配上的扩展配置（extra）。kwiki 认识的键由 kwiki 解释，其余键原样作为请求体参数
 * 透传给 provider；与专用字段或 kwiki 内部控制冲突的键被拒绝。合并时用途分配覆盖模型。
 */
public final class ModelExtra {

    /** embedding 每批条数（默认 {@link #DEFAULT_BATCH_SIZE}）。 */
    public static final String BATCH_SIZE = "batchSize";
    /** 覆盖 provider 的请求超时（毫秒）。 */
    public static final String TIMEOUT_MS = "timeoutMs";
    /** 阿里云百炼兼容模式 text-embedding-v3/v4 的单次上限。 */
    public static final int DEFAULT_BATCH_SIZE = 10;

    private static final Set<String> KNOWN = Set.of(BATCH_SIZE, TIMEOUT_MS);
    private static final Set<String> RESERVED = Set.of("model", "messages", "input", "stream", "stream_options",
            "tools", "tool_choice", "functions", "response_format", "temperature", "max_tokens",
            "max_completion_tokens", "enable_thinking", "n");
    private static final int MAX_ENTRIES = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ModelExtra() {
    }

    /** 校验并返回不可变副本；null 视为空。非法时抛出 IllegalArgumentException（消息可直接展示）。 */
    public static Map<String, Object> normalize(Map<String, ?> extra) {
        if (extra == null || extra.isEmpty()) return Map.of();
        if (extra.size() > MAX_ENTRIES) throw new IllegalArgumentException("额外参数最多 " + MAX_ENTRIES + " 项");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : extra.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_.\\-]{0,63}")) {
                throw new IllegalArgumentException("额外参数名不合法：" + entry.getKey());
            }
            if (RESERVED.contains(key)) {
                throw new IllegalArgumentException("额外参数不能使用 " + key + "（由专用字段或 kwiki 控制）");
            }
            Object value = entry.getValue();
            if (value == null) throw new IllegalArgumentException("额外参数 " + key + " 的值不能为空");
            if (BATCH_SIZE.equals(key)) requireInt(key, value, 1, 2048);
            if (TIMEOUT_MS.equals(key)) requireInt(key, value, 1000, 600_000);
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    /** 用途分配覆盖模型。 */
    public static Map<String, Object> merge(Map<String, Object> model, Map<String, Object> assignment) {
        if ((model == null || model.isEmpty()) && (assignment == null || assignment.isEmpty())) return Map.of();
        Map<String, Object> merged = new LinkedHashMap<>();
        if (model != null) merged.putAll(model);
        if (assignment != null) merged.putAll(assignment);
        return Collections.unmodifiableMap(merged);
    }

    /** 透传给 provider 的部分（排除 kwiki 自己解释的键），键有序以便参与指纹。 */
    public static Map<String, Object> passthrough(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) return Map.of();
        Map<String, Object> result = new TreeMap<>();
        extra.forEach((key, value) -> {
            if (!KNOWN.contains(key)) result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    public static Integer intValue(Map<String, Object> extra, String key) {
        Object value = extra == null ? null : extra.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    public static String toJson(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(extra);
        } catch (Exception e) {
            throw new IllegalArgumentException("额外参数无法序列化");
        }
    }

    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return Collections.unmodifiableMap(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("数据库中的额外参数不是合法的 JSON 对象");
        }
    }

    private static void requireInt(String key, Object value, int min, int max) {
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() < min || number.longValue() > max) {
            throw new IllegalArgumentException("额外参数 " + key + " 必须是 " + min + "–" + max + " 之间的整数");
        }
    }
}
