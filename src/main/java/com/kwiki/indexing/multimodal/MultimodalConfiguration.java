package com.kwiki.indexing.multimodal;

import com.kwiki.infrastructure.ai.QwenVisionSummaryClient;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.indexing.config.MultimodalIndexingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 多模态索引装配：仅在 kwiki.multimodal.enabled=true 时创建
 * 视觉客户端、安全下载器与协议实例，并在装配期做组合校验
 * （启用即要求可用的视觉模型配置——空白 api-key、非法 base-url
 * 或空白模型在启动时快速失败，而不是等第一个索引任务失败）。
 * 关闭时整个多模态路径不存在，kwiki-parse-2 流水线随之不可解析
 * （fail closed），旧版本行为完全不变。
 */
@Configuration
@ConditionalOnProperty(name = "kwiki.multimodal.enabled", havingValue = "true")
public class MultimodalConfiguration {

    @Bean
    public MultimodalMetrics multimodalMetrics(ObjectProvider<MeterRegistry> registry) {
        return new MultimodalMetrics(registry.getIfAvailable());
    }

    @Bean
    public ProtectedBlockProtocol protectedBlockProtocol(
            MultimodalIndexingProperties properties) {
        return new ProtectedBlockProtocol(properties.maxSummaryChars());
    }

    @Bean
    public QwenVisionSummaryClient qwenVisionSummaryClient(
            WebClient.Builder webClientBuilder,
            ExternalServicesProperties properties,
            MultimodalIndexingProperties multimodalProperties,
            MultimodalMetrics metrics) {
        multimodalProperties.requireUsableWhenEnabled(properties);
        ExternalServicesProperties.VisionModel vision = properties.visionModel();
        WebClient webClient = webClientBuilder.clone()
                .baseUrl(vision.baseUrl().replaceAll("/+$", "") + "/")
                .defaultHeader("Authorization", "Bearer " + vision.apiKey())
                .build();
        return new QwenVisionSummaryClient(webClient, vision.model(),
                vision.maxRetries(), vision.requestTimeout(), vision.concurrency(),
                metrics, multimodalProperties.maxSummaryChars());
    }

    @Bean
    public SafeExternalImageDownloader safeExternalImageDownloader(
            MultimodalIndexingProperties properties, MultimodalMetrics metrics) {
        return new SafeExternalImageDownloader(
                properties.maxExternalImageBytes(), properties.maxImagePixels(),
                properties.externalImageMaxRedirects(), properties.externalImageTimeout(),
                SafeExternalImageDownloader.normalizePorts(properties.externalImageAllowedPorts()),
                metrics);
    }
}
