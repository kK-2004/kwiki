package com.kwiki.rag.activity;

import com.kwiki.infrastructure.observability.SecretRedaction;
import com.kwiki.rag.orchestration.RunContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 覆盖 QA 门禁查询生命周期的结构化 DEBUG 日志。每条记录
 * 都携带关联标识（traceId/requestId/runId/sessionId，经由 MDC
 * + RunContext）、阶段标识（queryRound/attemptStage/phase）、耗时，
 * 以及有界且已脱敏的负载。RRF 分数与 QA 分数使用各自独立的
 * 字段；模型隐藏的推理过程从不请求、也不记录。当 DEBUG 处于
 * 禁用状态时，负载构造函数会被完全跳过（基于 supplier），因此
 * 热路径绝不会构造昂贵的 map。
 */
@Component
public class AgenticDebugLogger {

    public static final int MAX_SUMMARY_CHARS = 2000;
    public static final int MAX_QUERY_CHARS = 500;
    public static final int MAX_REASON_CHARS = 500;

    private static final Logger log = LoggerFactory.getLogger("kwiki.agentic.debug");

    public boolean enabled() {
        return log.isDebugEnabled();
    }

    /** 当前运行的关联标识，供后续日志记录使用。 */
    public Map<String, String> identity(RunContext run) {
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("traceId", MDC.get("traceId"));
        identity.put("requestId", run == null ? null : run.requestId);
        identity.put("runId", run == null ? null : run.requestId);
        return identity;
    }

    public void stage(RunContext run, String stageId, int queryRound, String attemptStage,
                      String phase, String status, long elapsedMs,
                      java.util.function.Supplier<Map<String, Object>> fields) {
        if (!log.isDebugEnabled()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stageId", stageId);
        entry.put("queryRound", queryRound);
        if (attemptStage != null) entry.put("attemptStage", attemptStage);
        entry.put("phase", phase);
        entry.put("status", status);
        entry.put("elapsedMs", elapsedMs);
        if (run != null) {
            entry.put("requestId", run.requestId);
            entry.put("budgetUsed", run.stats());
        }
        Map<String, Object> payload = fields == null ? Map.of() : fields.get();
        if (payload != null) entry.putAll(payload);
        log.debug("agentic-stage {}", ToolJson.of(entry));
    }

    public void terminal(RunContext run, String outcome, String reasonCode,
                         java.util.function.Supplier<Map<String, Object>> fields) {
        if (!log.isDebugEnabled()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("phase", "FINAL");
        entry.put("status", outcome);
        if (reasonCode != null) entry.put("reasonCode", reasonCode);
        if (run != null) {
            entry.put("requestId", run.requestId);
            entry.put("budgetUsed", run.stats());
        }
        Map<String, Object> payload = fields == null ? Map.of() : fields.get();
        if (payload != null) entry.putAll(payload);
        log.debug("agentic-terminal {}", ToolJson.of(entry));
    }

    /** 经脱敏且有长度上限的查询文本，用于诊断。 */
    public static String boundedQuery(String query) {
        return bounded(SecretRedaction.redact(query == null ? "" : query), MAX_QUERY_CHARS);
    }

    /** 经脱敏且限长的原因/摘要文本。 */
    public static String boundedReason(String reason) {
        return bounded(SecretRedaction.redact(reason == null ? "" : reason), MAX_REASON_CHARS);
    }

    /** 经脱敏的候选摘要，带 originalLength/truncated 标记。 */
    public static Map<String, Object> boundedSummary(String text) {
        String redacted = SecretRedaction.redact(text == null ? "" : text);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("originalLength", redacted.length());
        summary.put("truncated", redacted.length() > MAX_SUMMARY_CHARS);
        summary.put("text", bounded(redacted, MAX_SUMMARY_CHARS));
        return summary;
    }

    private static String bounded(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…[truncated]";
    }

    /** 极简 JSON 序列化，不在本类引入 mapper 依赖。 */
    private static final class ToolJson {
        static String of(Map<String, Object> entry) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> field : entry.entrySet()) {
                if (field.getValue() == null) {
                    continue;
                }
                if (!first) builder.append(",");
                first = false;
                builder.append("\"").append(field.getKey()).append("\":");
                appendValue(builder, field.getValue());
            }
            return builder.append("}").toString();
        }

        static void appendValue(StringBuilder builder, Object value) {
            if (value instanceof Number || value instanceof Boolean) {
                builder.append(value);
            } else if (value instanceof Map<?, ?> map) {
                builder.append("{");
                boolean first = true;
                for (Map.Entry<?, ?> field : map.entrySet()) {
                    if (field.getValue() == null) {
                        continue;
                    }
                    if (!first) builder.append(",");
                    first = false;
                    builder.append("\"").append(field.getKey()).append("\":");
                    appendValue(builder, field.getValue());
                }
                builder.append("}");
            } else if (value instanceof Iterable<?> items) {
                builder.append("[");
                boolean first = true;
                for (Object item : items) {
                    if (!first) builder.append(",");
                    first = false;
                    appendValue(builder, item);
                }
                builder.append("]");
            } else {
                appendString(builder, String.valueOf(value));
            }
        }

        static void appendString(StringBuilder builder, String value) {
            builder.append("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (c < 0x20) builder.append(String.format("\\u%04x", (int) c));
                        else builder.append(c);
                    }
                }
            }
            builder.append("\"");
        }
    }
}
