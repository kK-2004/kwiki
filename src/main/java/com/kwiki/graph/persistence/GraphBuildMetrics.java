package com.kwiki.graph.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** 图离线/在线流程的低基数指标；不把实体、正文、知识库内容或凭据写入 tag。 */
@Component
@ConditionalOnBean(MeterRegistry.class)
public class GraphBuildMetrics {

    private final MeterRegistry registry;
    private final AtomicLong cleanupBacklog = new AtomicLong();

    public GraphBuildMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("kwiki.graph.cleanup.backlog", cleanupBacklog);
    }

    public void recordStage(String stage, Duration elapsed, boolean success) {
        registry.timer("kwiki.graph.stage.duration", "stage", safe(stage),
                "result", success ? "success" : "failure").record(elapsed);
    }

    public void recordTarget(String target, String outcome) {
        registry.counter("kwiki.graph.target.total", "target", safe(target),
                "outcome", safe(outcome)).increment();
    }

    public void recordCommunityWrite(int count) {
        registry.counter("kwiki.graph.community.write.total").increment(count);
    }

    /** ES 实体映射回填覆盖率（0～1）与待回填积压。 */
    public void recordBackfillCoverage(long readySources, long totalSources) {
        registry.counter("kwiki.graph.backfill.ready.total").increment(readySources);
        registry.counter("kwiki.graph.backfill.total.total").increment(totalSources);
        cleanupBacklog.addAndGet(Math.max(0, totalSources - readySources));
    }

    /** 版本配对计数：CHUNK/COMMUNITY 双版本同时出现，便于审计对齐。 */
    public void recordVersionPair(int chunkIndexVersion, long communityIndexVersion,
                                  String outcome) {
        registry.counter("kwiki.graph.version.pair.total",
                "chunkVersion", String.valueOf(chunkIndexVersion),
                "communityVersion", String.valueOf(communityIndexVersion),
                "outcome", safe(outcome)).increment();
    }

    /** 离线模型成本（抽取/摘要/embedding 调用次数与重试）。 */
    public void recordModelCall(String purpose, boolean retry) {
        registry.counter("kwiki.graph.model.call.total", "purpose", safe(purpose),
                "retry", String.valueOf(retry)).increment();
    }

    /** 失败与清理积压。 */
    public void recordFailure(String stage) {
        registry.counter("kwiki.graph.failure.total", "stage", safe(stage)).increment();
    }

    public void recordCleanupBacklog(long pendingResources) {
        cleanupBacklog.set(pendingResources);
    }

    /** 在线增强轨迹：ES 种子提取、图扩展、证据验证的耗时与降级原因。 */
    public void recordEnhancement(String step, Duration elapsed, String outcome) {
        registry.timer("kwiki.graph.enhancement.duration", "step", safe(step),
                "outcome", safe(outcome)).record(elapsed);
        registry.counter("kwiki.graph.enhancement.total", "step", safe(step),
                "outcome", safe(outcome)).increment();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
