package com.kwiki.graph.config;

import com.kwiki.graph.GraphAlgorithmMode;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 图增强总开关和不依赖外部服务的运行配置。 */
@ConfigurationProperties(prefix = "kwiki.graph")
@Validated
public record GraphProperties(
    @NotNull @DefaultValue("false") Boolean enabled,
    @NotNull @DefaultValue("ARCADEDB_NATIVE_UNWEIGHTED") GraphAlgorithmMode algorithmMode,
    @NotNull @DefaultValue("0 0 2 * * *") String scheduleCron,
    @NotNull @DefaultValue("Asia/Shanghai") String scheduleZone,
    @NotNull @DefaultValue("false") Boolean autoPublish,
    @NotNull @DefaultValue("10m") java.time.Duration readLeaseDuration,
    @NotNull @DefaultValue("2") int retentionMinSnapshots,
    @NotNull @DefaultValue("30m") java.time.Duration retirementGrace,
    @NotNull @DefaultValue Capacity capacity) {

    public GraphProperties {
        if (retentionMinSnapshots < 1) {
            throw new IllegalArgumentException("快照最少保留数无效");
        }
    }

    public record Capacity(
            @DefaultValue("50000") int maxEntities,
            @DefaultValue("250000") int maxRelations,
            @DefaultValue("1000000") int maxRelationSources,
            @DefaultValue("5000") int maxCommunities,
            @DefaultValue("20000") int warnEntities,
            @DefaultValue("100000") int warnRelations,
            @DefaultValue("2") int maxHops,
            @DefaultValue("50") int maxEdgesPerNode,
            @DefaultValue("500") int maxEdgesTotal) {
        public Capacity {
            if (maxEntities < 1 || maxRelations < 1 || maxRelationSources < 1
                    || maxCommunities < 1 || warnEntities < 0 || warnRelations < 0
                    || maxHops < 1 || maxHops > 2 || maxEdgesPerNode < 1 || maxEdgesTotal < 1) {
                throw new IllegalArgumentException("图容量或预算配置无效");
            }
        }

        public static Capacity defaults() {
            return new Capacity(50_000, 250_000, 1_000_000, 5_000,
                    20_000, 100_000, 2, 50, 500);
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
