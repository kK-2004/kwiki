package com.kwiki.graph.plugin;

import com.kwiki.graph.ArcadeDbCapabilityReport;
import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.infrastructure.arcadedb.ArcadeDbCapabilityProbe;
import com.kwiki.infrastructure.arcadedb.ArcadeDbNativeCommunityDetection;
import com.kwiki.indexing.plugin.PluginAvailability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 首个社区检测插件：包装 ArcadeDB 原生无权 Leiden；可用性结果缓存一分钟。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbLeidenV1 implements CommunityDetectorPlugin {

    public static final String ID = "arcadedb-leiden-1";

    private static final long AVAILABILITY_CACHE_NANOS = 60_000_000_000L;

    private final ObjectProvider<ArcadeDbNativeCommunityDetection> detectorProvider;
    private final ObjectProvider<ArcadeDbCapabilityProbe> probeProvider;
    private volatile PluginAvailability cachedAvailability;
    private volatile long cachedAtNanos;

    public ArcadeDbLeidenV1(ObjectProvider<ArcadeDbNativeCommunityDetection> detectorProvider,
                            ObjectProvider<ArcadeDbCapabilityProbe> probeProvider) {
        this.detectorProvider = detectorProvider;
        this.probeProvider = probeProvider;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "ArcadeDB Leiden v1";
    }

    @Override
    public PluginAvailability availability() {
        long now = System.nanoTime();
        PluginAvailability cached = cachedAvailability;
        if (cached != null && now - cachedAtNanos < AVAILABILITY_CACHE_NANOS) {
            return cached;
        }
        PluginAvailability fresh = computeAvailability();
        cachedAvailability = fresh;
        cachedAtNanos = now;
        return fresh;
    }

    @Override
    public CommunityDetectionResult detect(CommunityDetectionInput input) {
        ArcadeDbNativeCommunityDetection detector = detectorProvider.getIfAvailable();
        if (detector == null) {
            throw new IllegalStateException("ArcadeDB 社区检测未配置");
        }
        return detector.detect(input);
    }

    private PluginAvailability computeAvailability() {
        if (detectorProvider.getIfAvailable() == null || probeProvider.getIfAvailable() == null) {
            return PluginAvailability.unavailable("ArcadeDB 未配置");
        }
        ArcadeDbCapabilityReport report = probeProvider.getObject().probe();
        if (!report.reachable()) {
            return PluginAvailability.unavailable(
                    "ArcadeDB 不可达（" + report.failureCode() + "）");
        }
        if (!report.leidenSupported()) {
            return PluginAvailability.unavailable(
                    "ArcadeDB 不支持原生 Leiden 算法（" + report.failureCode() + "）");
        }
        if ("database-missing".equals(report.failureCode())) {
            return PluginAvailability.unavailable(
                    "ArcadeDB 业务库或计算库不存在、或 token 无权访问（database-missing）");
        }
        if (!report.buildEnabled()) {
            return PluginAvailability.unavailable(
                    "ArcadeDB 构建能力不可用（" + report.failureCode() + "）");
        }
        return PluginAvailability.ok();
    }
}
