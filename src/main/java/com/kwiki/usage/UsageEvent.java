package com.kwiki.usage;

import java.time.Instant;

/** 一次模型调用的用量事件；缺失值归一化，负数归零。 */
public record UsageEvent(
        Instant occurredAt,
        UsageSource source,
        String step,
        String provider,
        String model,
        long userId,
        String requestId,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        boolean success,
        String errorType) {

    public UsageEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        source = source == null ? UsageSource.BACKGROUND : source;
        step = blankToUnknown(step);
        provider = blankToUnknown(provider);
        model = blankToUnknown(model);
        userId = Math.max(0, userId);
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        latencyMs = Math.max(0, latencyMs);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
