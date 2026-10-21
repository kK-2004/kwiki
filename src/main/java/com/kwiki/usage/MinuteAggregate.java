package com.kwiki.usage;

import java.time.Instant;

/** 分钟级聚合行（bucketStart 为截断到分钟的 UTC 时刻）。 */
public record MinuteAggregate(
        Instant bucketStart,
        UsageSource source,
        String step,
        String provider,
        String model,
        long userId,
        long calls,
        long failures,
        long inputTokens,
        long outputTokens,
        long latencyMs) {
}
