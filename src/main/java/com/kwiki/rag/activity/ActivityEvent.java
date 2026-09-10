package com.kwiki.rag.activity;

import java.util.Map;

/**
 * Versioned activity event streamed to the browser over the SSE {@code activity}
 * channel and persisted with the assistant run for replay. Steps merge by
 * {@code stepId}: a started event is superseded by the completed/skipped/failed
 * event of the same step; the sequence number only orders delivery. Metrics use
 * explicitly named keys (branch, branchTopK, hitCount, fusedCandidateCount,
 * retainedChildCount, parentCount, ...) — missing data is omitted, never
 * invented. Internal candidate bodies, prompts, tool arguments, and secrets are
 * never part of an activity payload.
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
