package com.kwiki.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 面向运维方提供的中间件，由环境变量显式注入的连接配置。
 * kwiki 绝不创建 MySQL、Redis、内容中心或 Elasticsearch；每一项
 * 取值都必须来自 application.yml 中映射的 KWIKI_* 环境变量。Bean
 * 校验在绑定时执行，因此配置错误的部署会在
 * 接受流量之前就失败。
 */
@ConfigurationProperties(prefix = "kwiki")
@Validated
public record ExternalServicesProperties(
        @Valid @NotNull ContentCenter contentCenter,
        @Valid @NotNull Elasticsearch elasticsearch,
        @Valid @NotNull AnswerLlm answerLlm,
        @Valid @NotNull QwenEmbedding qwenEmbedding) {

    /**
     * 内容中心（k-File）的附件存储（attachment storage）。app token 对全部
     * open-API 调用进行认证；source 与 path 是内容中心
     * 所有的可选上传路由提示。校验过程绝不在消息中回显令牌。
     */
    public record ContentCenter(
            @NotBlank
            @Pattern(regexp = "https?://\\S+", message = "must be an http(s) base URL")
            String baseUrl,
            @NotBlank String appToken,
            String source,
            String path,
            @NotNull @DefaultValue("10s") Duration connectTimeout,
            @NotNull @DefaultValue("600s") Duration requestTimeout) {
    }

    public record Elasticsearch(
            String apiKey,
            String username,
            String password) {
    }

    public record AnswerLlm(
            @NotBlank
            @Pattern(regexp = "https?://\\S+", message = "must be an http(s) base URL")
            String baseUrl,
            @NotBlank String apiKey,
            @NotBlank String model,
            @NotNull @DefaultValue("120s") Duration requestTimeout) {
    }

    /**
     * Qwen embedding 配置。API key 必须是 kwiki 专属的（绝不可从
     * k-Rag 复制），模型固定为 text-embedding-v4，单一维度值同时驱动
     * embedding 响应校验与 Elasticsearch 的稠密向量映射。
     */
    public record QwenEmbedding(
            @NotBlank
            @Pattern(regexp = "https?://\\S+", message = "must be an http(s) base URL")
            @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1")
            String baseUrl,
            @NotBlank String apiKey,
            @NotBlank
            @NotBlank
            String model,
            @NotNull @Min(64) @Max(2048) @DefaultValue("1024") Integer dimensions,
            @NotNull @DefaultValue("30s") Duration requestTimeout) {
    }
}
