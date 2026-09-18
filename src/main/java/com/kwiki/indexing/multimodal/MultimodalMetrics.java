package com.kwiki.indexing.multimodal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * 多模态索引的可观测边界：所有计数/耗时/结构化日志都经由本类
 * 出口，且绝不携带 API Key、完整 CDN URL、外部原始 URL 或
 * 请求体内容。stage 标签使用固定的低基数值。
 */
public class MultimodalMetrics {

    /** 图片生命周期计数器的固定取值（写入 ES 的 resourceStage）。 */
    public static final String STAGE_EXTRACTED = "extracted";
    public static final String STAGE_FILTERED_MASK = "filtered-mask";
    public static final String STAGE_FILTERED_SMALL = "filtered-small";
    public static final String STAGE_FILTERED_LARGE = "filtered-large";
    public static final String STAGE_FILTERED_CORRUPT = "filtered-corrupt";
    public static final String STAGE_DEDUPLICATED = "deduplicated";
    public static final String STAGE_UPLOADED = "uploaded";
    public static final String STAGE_REUSED = "reused";
    public static final String STAGE_SUMMARIZED = "summarized";

    private final MeterRegistry registry;

    public MultimodalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一张图片经过某个生命周期阶段。 */
    public void imageStage(String stage) {
        if (registry != null) {
            registry.counter("kwiki_multimodal_images_total", "stage", stage).increment();
        }
    }

    /** 外链抓取被地址策略拒绝（SSRF 防护命中）。 */
    public void ssrfDenied(String reason) {
        if (registry != null) {
            registry.counter("kwiki_multimodal_ssrf_denies_total", "reason", reason).increment();
        }
    }

    /** 视觉摘要调用耗时与结果。 */
    public void visionCall(String outcome, Duration latency) {
        if (registry != null) {
            registry.counter("kwiki_multimodal_vision_calls_total", "outcome", outcome).increment();
            Timer.builder("kwiki_multimodal_vision_latency")
                    .description("vision summary call latency")
                    .register(registry)
                    .record(latency);
        }
    }

    /** 受保护块超出常规分块上限、被单独成块。 */
    public void oversizedProtectedBlock() {
        if (registry != null) {
            registry.counter("kwiki_multimodal_oversized_blocks_total").increment();
        }
    }

    /** 可审计的清理候选（内容中心暂无删除能力时的降级路径）。 */
    public void cleanupCandidate() {
        if (registry != null) {
            registry.counter("kwiki_multimodal_cleanup_candidates_total").increment();
        }
    }

    /** 多模态阶段失败（瞬时/永久分类由异常承担，这里只计数）。 */
    public void stageFailure(String stage, String classification) {
        if (registry != null) {
            registry.counter("kwiki_multimodal_failures_total",
                    "stage", stage, "classification", classification).increment();
        }
    }
}
