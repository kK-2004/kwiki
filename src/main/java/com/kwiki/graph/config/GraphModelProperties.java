package com.kwiki.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 图构建离线模型的独立连接配置；连接项为空时沿用问答模型。 */
@ConfigurationProperties("kwiki.graph.models")
public record GraphModelProperties(@DefaultValue Model extraction, @DefaultValue Model summary) {

    /**
     * 连接项（baseUrl / apiKey / model / requestTimeout）为空时沿用问答模型。
     * 并发、QPS、重试为空时由 {@code GraphModelClients} 应用各端点的缺省值。
     */
    public record Model(String baseUrl, String apiKey, String model, Duration requestTimeout,
                        Integer maxConcurrency, Double qps, Integer maxRetries) {
    }
}
