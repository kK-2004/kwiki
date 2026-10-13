package com.kwiki.infrastructure.ai.registry;

import com.kwiki.graph.model.RateLimiter;

import java.time.Duration;
import java.util.Map;

/**
 * 某个用途在当前快照中解析出的模型与参数（extra 为模型与用途合并后的扩展配置）。apiKey 仅供需要自建 HTTP 客户端的调用方
 * （如图片摘要）使用，toString 不输出。limiter 只在 provider 配置了并发上限时非空。
 */
public record ResolvedModel(ModelPurpose purpose, long providerId, String providerName, String baseUrl,
                            String apiKey, String modelName, double temperature, Integer maxTokens,
                            Boolean thinking, int maxRetries, Duration timeout, RateLimiter limiter,
                            Integer providerConcurrency, Map<String, Object> extra) {

    public ResolvedModel {
        extra = extra == null ? Map.of() : extra;
    }

    @Override
    public String toString() {
        return "ResolvedModel[purpose=" + purpose + ", provider=" + providerName + ", model=" + modelName
                + ", temperature=" + temperature + ", maxTokens=" + maxTokens + ", thinking=" + thinking
                + ", extra=" + extra.keySet() + "]";
    }
}
