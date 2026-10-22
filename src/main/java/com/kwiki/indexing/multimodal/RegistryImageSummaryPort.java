package com.kwiki.indexing.multimodal;

import com.kwiki.infrastructure.ai.QwenVisionSummaryClient;
import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;
import com.kwiki.infrastructure.ai.registry.ResolvedModel;
import com.kwiki.usage.ModelUsageRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按 {@link ModelPurpose#VISION_SUMMARY} 的当前分配执行图片摘要。底层客户端按
 * provider 地址、key、模型与连接参数缓存，分配变化后下一次调用即换用新客户端。
 * 未分配时以明确失败的方式拒绝，管理端与别名切换门禁会显示缺失项。
 */
public class RegistryImageSummaryPort implements ImageSummaryPort {

    private static final int DEFAULT_CONCURRENCY = 4;

    private final ModelRegistry models;
    private final WebClient.Builder webClientBuilder;
    private final MultimodalMetrics metrics;
    private final int maxSummaryChars;
    private final ObjectProvider<ModelUsageRecorder> usageRecorder;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public RegistryImageSummaryPort(ModelRegistry models, WebClient.Builder webClientBuilder,
                                    MultimodalMetrics metrics, int maxSummaryChars,
                                    ObjectProvider<ModelUsageRecorder> usageRecorder) {
        this.models = models;
        this.webClientBuilder = webClientBuilder;
        this.metrics = metrics;
        this.maxSummaryChars = maxSummaryChars;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public String summarize(String cdnUrl) {
        ResolvedModel model = models.find(ModelPurpose.VISION_SUMMARY).orElseThrow(() ->
                new IllegalStateException("vision model configuration is unavailable"));
        return client(model).summarize(cdnUrl);
    }

    @Override
    public String modelName() {
        return models.find(ModelPurpose.VISION_SUMMARY).map(ResolvedModel::modelName).orElse("unconfigured");
    }

    private QwenVisionSummaryClient client(ResolvedModel model) {
        Key key = new Key(model.baseUrl(), model.apiKey(), model.providerName(), model.modelName(),
                model.maxRetries(),
                model.timeout().toMillis(),
                model.providerConcurrency() == null ? DEFAULT_CONCURRENCY : model.providerConcurrency());
        Cached current = cached.get();
        if (current != null && current.key().equals(key)) {
            return current.client();
        }
        WebClient webClient = webClientBuilder.clone()
                .baseUrl(model.baseUrl().replaceAll("/+$", "") + "/")
                .defaultHeader("Authorization", "Bearer " + model.apiKey())
                .build();
        QwenVisionSummaryClient client = new QwenVisionSummaryClient(webClient, model.modelName(),
                model.maxRetries(), model.timeout(), key.concurrency(), metrics, maxSummaryChars,
                model.providerName(), usageRecorder == null ? null : usageRecorder.getIfAvailable());
        cached.set(new Cached(key, client));
        return client;
    }

    private record Key(String baseUrl, String apiKey, String provider, String model, int retries, long timeoutMs, int concurrency) {
        @Override
        public String toString() {
            return "Key[" + baseUrl + ", " + model + "]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key k && baseUrl.equals(k.baseUrl) && apiKey.equals(k.apiKey)
                    && Objects.equals(provider, k.provider) && model.equals(k.model) && retries == k.retries && timeoutMs == k.timeoutMs
                    && concurrency == k.concurrency;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseUrl, provider, model, retries, timeoutMs, concurrency);
        }
    }

    private record Cached(Key key, QwenVisionSummaryClient client) {
    }
}
