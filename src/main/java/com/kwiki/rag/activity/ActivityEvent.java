package com.kwiki.rag.activity;

import java.util.Map;

/**
 * 带版本号的活动事件，通过 SSE {@code activity} 通道推送到浏览器
 * 并随助手运行（run）一起持久化以便回放。步骤按
 * {@code stepId} 合并：同一步骤的 started 事件会被 completed/skipped/failed 事件取代；
 * 同一步骤的事件；序号仅用于排序投递。指标使用
 * 显式命名的 key（branch、branchTopK、hitCount、fusedCandidateCount、
 * retainedChildCount、parentCount 等）——缺失数据直接省略，绝不
 * 臆造。内部候选体、提示词、工具参数与密钥
 * 绝不出现在活动载荷中。
 */
public record ActivityEvent(
        int schemaVersion,
        String stepId,
        String parentStepId,
        int queryRound,
        String attemptStage,
        Phase phase,
        Status status,
        long startedAt,
        Long durationMs,
        String summary,
        Map<String, Object> metrics,
        String reasonCode) {

    public static final int SCHEMA_VERSION = 1;

    public enum Phase {
        ROUTE,
        RETRIEVAL,
        GENERATION,
        QUALITY,
        PARENT_FETCH,
        EXPANSION,
        REWRITE,
        FINAL
    }

    public enum Status {
        STARTED,
        COMPLETED,
        SKIPPED,
        FAILED
    }

    public ActivityEvent {
        metrics = metrics == null || metrics.isEmpty() ? Map.of() : Map.copyOf(metrics);
        if (durationMs != null && durationMs < 0) durationMs = 0L;
    }

    public static ActivityEvent started(String stepId, String parentStepId, int queryRound,
                                        String attemptStage, Phase phase, long startedAt,
                                        String summary) {
        return new ActivityEvent(SCHEMA_VERSION, stepId, parentStepId, queryRound,
                attemptStage, phase, Status.STARTED, startedAt, null, summary, Map.of(), null);
    }

    public static ActivityEvent finished(String stepId, String parentStepId, int queryRound,
                                         String attemptStage, Phase phase, Status status,
                                         long startedAt, Long durationMs, String summary,
                                         Map<String, Object> metrics, String reasonCode) {
        return new ActivityEvent(SCHEMA_VERSION, stepId, parentStepId, queryRound,
                attemptStage, phase, status, startedAt, durationMs, summary, metrics, reasonCode);
    }

    public Map<String, Object> toPayload() {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("stepId", stepId);
        if (parentStepId != null) payload.put("parentStepId", parentStepId);
        payload.put("queryRound", queryRound);
        if (attemptStage != null) payload.put("attemptStage", attemptStage);
        payload.put("phase", phase.name());
        payload.put("status", status.name());
        payload.put("startedAt", startedAt);
        if (durationMs != null) payload.put("durationMs", durationMs);
        if (summary != null) payload.put("summary", summary);
        if (!metrics.isEmpty()) payload.put("metrics", metrics);
        if (reasonCode != null) payload.put("reasonCode", reasonCode);
        return payload;
    }
}
