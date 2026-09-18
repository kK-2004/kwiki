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
        @Valid @NotNull QwenEmbedding qwenEmbedding,
        /** 缺省绑定（空 vision-model 键）时以全默认值构造；启用多模态时再校验凭据。 */
        @Valid @NotNull @DefaultValue VisionModel visionModel) {

    /** 测试/装配辅助：视觉模型取未配置但校验可通过的默认形态。 */
    public static VisionModel unusedVisionModel() {
        return new VisionModel(null, null, "qwen3.7-flash",
                Duration.ofSeconds(5), Duration.ofSeconds(60), 2, 4);
    }

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
     * k-Rag 复制），模型与维度显式配置，单一维度值同时驱动
     * embedding 响应校验与 Elasticsearch 的稠密向量映射。
     */
    public record QwenEmbedding(
            @NotBlank
            @Pattern(regexp = "https?://\\S+", message = "must be an http(s) base URL")
            @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1")
            String baseUrl,
            @NotBlank String apiKey,
            @NotBlank
            String model,
            @NotNull @Min(64) @Max(2048) @DefaultValue("1024") Integer dimensions,
            @NotNull @DefaultValue("30s") Duration requestTimeout) {
    }

    /**
     * 图片摘要视觉模型的独立配置（OpenAI-compatible
     * /chat/completions）。与回答模型、embedding 完全解耦：
     * 独立凭据、超时与并发生命周期。base-url 与 api-key 在
     * 多模态索引关闭（kwiki.multimodal.enabled=false，默认）时
     * 允许为空；启用时由多模态装配做启动期校验并快速失败。
     * api key 绝不出现在日志、异常或指标中。
     */
    public record VisionModel(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @NotBlank
            @DefaultValue("qwen3.7-flash")
            String model,
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("60s") Duration requestTimeout,
            @NotNull @Min(0) @Max(10) @DefaultValue("2") Integer maxRetries,
            @NotNull @Min(1) @Max(64) @DefaultValue("4") Integer concurrency) {

        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank()
                    && baseUrl.matches("https?://\\S+")
                    && apiKey != null && !apiKey.isBlank();
        }
    }
}
