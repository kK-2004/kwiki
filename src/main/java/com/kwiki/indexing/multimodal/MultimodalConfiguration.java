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
 * 视觉客户端、安全下载器与协议实例；视觉模型配置缺失时
 * 使用明确失败的端口，管理端与别名切换门禁会显示缺失项并拒绝 v2。
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
    public ImageSummaryPort qwenVisionSummaryClient(
            WebClient.Builder webClientBuilder,
            ExternalServicesProperties properties,
            MultimodalIndexingProperties multimodalProperties,
            MultimodalMetrics metrics) {
        ExternalServicesProperties.VisionModel vision = properties.visionModel();
        if (vision == null || !vision.isConfigured()) {
            // 管理端需要启动后展示缺失项；误入摘要路径仍须明确失败。
            return cdnUrl -> {
                throw new IllegalStateException("vision model configuration is unavailable");
            };
        }
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
