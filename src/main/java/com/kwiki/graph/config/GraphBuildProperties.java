package com.kwiki.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 图构建执行器的领取、租约、重试与调用量上限配置。 */
@ConfigurationProperties("kwiki.graph.build")
public record GraphBuildProperties(
        @DefaultValue("10s") Duration pollInterval,
        @DefaultValue("2") int maxConcurrentRuns,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1s") Duration retryBaseDelay,
        @DefaultValue("20000") int maxChunksPerRun,
        @DefaultValue("5000") int maxSummariesPerRun) {

    public GraphBuildProperties {
        if (maxConcurrentRuns < 1 || maxAttempts < 1
                || pollInterval.isNegative() || pollInterval.isZero()
                || leaseDuration.isNegative() || leaseDuration.isZero()
                || retryBaseDelay.isNegative() || maxChunksPerRun < 1 || maxSummariesPerRun < 1) {
            throw new IllegalArgumentException("图构建执行配置无效");
        }
    }
}
