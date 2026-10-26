package com.kwiki.graph.model;

import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;
import com.kwiki.infrastructure.ai.registry.ResolvedModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 图构建离线模型端点：抽取与摘要分别对应 {@link ModelPurpose#GRAPH_EXTRACTION} 与
 * {@link ModelPurpose#GRAPH_SUMMARY}，每次调用时从模型目录解析，后台修改即时生效。
 * provider 配置了并发上限时共享 provider 级限流器，否则使用各端点的缺省限流器。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphModelClients {

    private static final int EXTRACTION_MAX_CONCURRENCY = 4;
    private static final double EXTRACTION_QPS = 5.0;
    private static final int SUMMARY_MAX_CONCURRENCY = 2;
    private static final double SUMMARY_QPS = 2.0;

    private final ModelRegistry models;
    /** 遇到网关超时后改走流式的端点标记，进程内有效。 */
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> streamingPreference =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final RateLimiter extractionLimiter = new RateLimiter(EXTRACTION_MAX_CONCURRENCY, EXTRACTION_QPS);
    private final RateLimiter summaryLimiter = new RateLimiter(SUMMARY_MAX_CONCURRENCY, SUMMARY_QPS);

    public GraphModelClients(ModelRegistry models) {
        this.models = models;
    }

    public Optional<ModelEndpoint> extraction() {
        return endpoint(ModelPurpose.GRAPH_EXTRACTION, extractionLimiter);
    }

    public Optional<ModelEndpoint> summary() {
        return endpoint(ModelPurpose.GRAPH_SUMMARY, summaryLimiter);
    }

    /** 不可用时的原因（如「GRAPH_EXTRACTION（图谱抽取）未分配可用模型」），用于插件可用性展示。 */
    public List<String> missing(String purpose) {
        ModelPurpose target = switch (purpose == null ? "" : purpose) {
            case "extraction" -> ModelPurpose.GRAPH_EXTRACTION;
            case "summary" -> ModelPurpose.GRAPH_SUMMARY;
            default -> throw new IllegalArgumentException("未知的模型用途：" + purpose);
        };
        return models.find(target).isPresent()
                ? List.of()
                : List.of(target.name() + "（" + target.label() + "）未分配可用模型");
    }

    private Optional<ModelEndpoint> endpoint(ModelPurpose purpose, RateLimiter fallbackLimiter) {
        Optional<ResolvedModel> resolved = models.find(purpose);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        ResolvedModel model = resolved.get();
        RateLimiter limiter = model.limiter() == null ? fallbackLimiter : model.limiter();
        dev.langchain4j.model.chat.StreamingChatModel streaming;
        try {
            streaming = models.streaming(purpose);
        } catch (RuntimeException unavailable) {
            streaming = null;
        }
        // 按「用途 + 地址 + 模型」共享流式偏好：换了模型或地址即重新从非流式开始
        java.util.concurrent.atomic.AtomicBoolean preferStreaming = streamingPreference.computeIfAbsent(
                purpose.name() + "|" + model.baseUrl() + "|" + model.modelName(),
                key -> new java.util.concurrent.atomic.AtomicBoolean());
        return Optional.of(new ModelEndpoint(models.chat(purpose), limiter, model.maxRetries(), model.modelName(),
                streaming, model.timeout(), preferStreaming));
    }
}
