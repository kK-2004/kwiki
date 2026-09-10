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
 * Explicit, environment-injected connection settings for operator-provided middleware.
 * kwiki never provisions MySQL, Redis, the content center, or Elasticsearch; every
 * value must come from KWIKI_* environment variables mapped in application.yml. Bean
 * Validation runs at binding time so a misconfigured deployment fails before
 * accepting traffic.
 */
@ConfigurationProperties(prefix = "kwiki")
@Validated
public record ExternalServicesProperties(
        @Valid @NotNull ContentCenter contentCenter,
        @Valid @NotNull Elasticsearch elasticsearch,
        @Valid @NotNull AnswerLlm answerLlm,
        @Valid @NotNull QwenEmbedding qwenEmbedding) {

    /**
     * Content center (k-File) attachment storage. The app token authenticates all
     * open-API calls; source and path are optional upload routing hints owned by the
     * content center. Validation never echoes the token in messages.
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
     * Qwen embedding settings. The API key must be kwiki-specific (never copied from
     * k-Rag), the model is fixed to text-embedding-v4, and the single dimension value
     * drives both embedding response validation and the Elasticsearch dense-vector mapping.
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
