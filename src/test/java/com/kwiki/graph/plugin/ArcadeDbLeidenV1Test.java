package com.kwiki.graph.plugin;

import com.kwiki.graph.ArcadeDbCapabilityReport;
import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.infrastructure.arcadedb.ArcadeDbCapabilityProbe;
import com.kwiki.infrastructure.arcadedb.ArcadeDbNativeCommunityDetection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ArcadeDB Leiden 插件的可用性原因、缓存与委托。 */
class ArcadeDbLeidenV1Test {

    private static CommunityDetectionInput input() {
        return new CommunityDetectionInput(7L, 2L, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                10, 1.0, "hash", "12", 3,
                new com.kwiki.graph.UnweightedGraphProjection(7L, 2L, List.of(), List.of(), "hash"));
    }

    private static ArcadeDbCapabilityReport report(boolean reachable, boolean leiden,
                                                   boolean buildEnabled, String failureCode) {
        return new ArcadeDbCapabilityReport(reachable, "25.1", reachable, leiden, reachable,
                reachable, buildEnabled, failureCode);
    }

    @Test
    void 检测器缺失时不可用原因为未配置() {
        ArcadeDbLeidenV1 plugin = new ArcadeDbLeidenV1(emptyProvider(), emptyProvider());

        assertThat(plugin.availability().available()).isFalse();
        assertThat(plugin.availability().unavailableReason()).contains("ArcadeDB 未配置");
    }

    @Test
    void 探测不可达与不支持时的中文原因附带失败码() {
        ArcadeDbCapabilityProbe unreachable = probe(report(false, false, false, "connection-refused"));
        ArcadeDbCapabilityProbe noLeiden = probe(report(true, false, false, "leiden-capability-missing"));
        ArcadeDbCapabilityProbe buildDisabled = probe(report(true, true, false, "schema-capability-missing"));

        assertThat(new ArcadeDbLeidenV1(provider(mockDetector()),
                provider(unreachable)).availability().unavailableReason())
                .contains("不可达").contains("connection-refused");
        assertThat(new ArcadeDbLeidenV1(provider(mockDetector()),
                provider(noLeiden)).availability().unavailableReason())
                .contains("Leiden").contains("leiden-capability-missing");
        assertThat(new ArcadeDbLeidenV1(provider(mockDetector()),
                provider(buildDisabled)).availability().unavailableReason())
                .contains("构建能力").contains("schema-capability-missing");
    }

    private static ArcadeDbNativeCommunityDetection mockDetector() {
        return mock(ArcadeDbNativeCommunityDetection.class);
    }

    @Test
    void 能力齐全时可用且探测结果缓存一分钟() {
        AtomicInteger probes = new AtomicInteger();
        ArcadeDbCapabilityProbe okProbe = probe(answers -> {
            probes.incrementAndGet();
            return report(true, true, true, "");
        });
        ArcadeDbLeidenV1 plugin = new ArcadeDbLeidenV1(
                provider(mock(ArcadeDbNativeCommunityDetection.class)), provider(okProbe));

        assertThat(plugin.availability().available()).isTrue();
        assertThat(plugin.availability().available()).isTrue();
        assertThat(plugin.availability().available()).isTrue();
        // 可用性结果缓存 1 分钟，避免每次列插件都探测
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void 检测委托给原生实现() {
        ArcadeDbNativeCommunityDetection detector = mock(ArcadeDbNativeCommunityDetection.class);
        CommunityDetectionResult expected = new CommunityDetectionResult(
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "25.1", List.of());
        when(detector.detect(any())).thenReturn(expected);
        ArcadeDbLeidenV1 plugin = new ArcadeDbLeidenV1(provider(detector),
                provider(probe(report(true, true, true, ""))));

        assertThat(plugin.detect(input())).isSameAs(expected);
    }

    private static ArcadeDbCapabilityProbe probe(ArcadeDbCapabilityReport report) {
        return probe(ignored -> report);
    }

    /** 探测类是 final 的，用 Mockito 打桩并统计探测次数。 */
    private static ArcadeDbCapabilityProbe probe(
            java.util.function.Function<ArcadeDbCapabilityProbe, ArcadeDbCapabilityReport> answer) {
        ArcadeDbCapabilityProbe probe = mock(ArcadeDbCapabilityProbe.class);
        when(probe.probe()).thenAnswer(invocation -> answer.apply(probe));
        return probe;
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new IllegalStateException("无可用 Bean");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }
        };
    }
}
