package com.kwiki.graph.persistence;

import java.time.Duration;
import java.time.Instant;

/** 批次超过六小时只产生预警，不把长批次静默当作成功。 */
public final class GraphBatchAgePolicy {

    private GraphBatchAgePolicy() {
    }

    public static AgeStatus evaluate(Instant createdAt, Instant now) {
        if (createdAt == null || now == null || now.isBefore(createdAt)) {
            throw new IllegalArgumentException("批次时间无效");
        }
        Duration age = Duration.between(createdAt, now);
        return new AgeStatus(age, age.compareTo(Duration.ofHours(6)) > 0);
    }

    public record AgeStatus(Duration age, boolean warning) {
    }
}
