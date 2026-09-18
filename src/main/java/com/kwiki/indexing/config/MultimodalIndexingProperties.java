package com.kwiki.indexing.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/**
 * 多模态文档索引（PDF 内嵌图片 + 发布后 Markdown 图片）的部署开关与
 * 资源边界。默认整体关闭：开启前必须提供有效的视觉模型配置
 * （kwiki.vision-model.*）。所有限额都是显式失败边界——达到上限的
 * 文档让索引任务失败，而不是静默截断图片语义。
 */
@ConfigurationProperties(prefix = "kwiki.multimodal")
@Validated
public record MultimodalIndexingProperties(
        /** 功能总开关；关闭时解析、抓取、上传与摘要路径全部旁路。 */
        @NotNull @DefaultValue("false") Boolean enabled,
        /** 摘要提示词版本；参与派生摘要的持久身份，变更须经索引重建生效。 */
        @NotBlank @DefaultValue("v1") String promptVersion,
        /** 单文档去重后的图片上限；超出即永久失败。 */
        @NotNull @Min(1) @Max(500) @DefaultValue("20") Integer maxImagesPerDocument,
        /** 低于该像素数的图片按装饰资源过滤（计入指标）。 */
        @NotNull @Min(1) @DefaultValue("1024") Integer minImagePixels,
        /** 解码像素上限（压缩炸弹防护的一部分）。 */
        @NotNull @Max(33554432) @DefaultValue("4194304") Integer maxImagePixels,
        /** 单张派生图片字节上限（规范化编码后）。 */
        @NotNull @Max(52428800) @DefaultValue("10485760") Integer maxImageBytes,
        /** 视觉摘要的最大字符数；超限摘要按永久失败处理。 */
        @NotNull @Min(16) @Max(8192) @DefaultValue("512") Integer maxSummaryChars,
        /** 外链图片抓取的响应字节上限。 */
        @NotNull @Max(52428800) @DefaultValue("10485760") Integer maxExternalImageBytes,
        /** 外链图片整体抓取时限（含全部重定向）。 */
        @NotNull @DefaultValue("20s") Duration externalImageTimeout,
        /** 外链图片重定向上限；每一跳都重新执行地址策略。 */
        @NotNull @Min(0) @Max(10) @DefaultValue("3") Integer externalImageMaxRedirects,
        /** 允许的外链端口；默认仅标准 HTTPS 端口。 */
        @NotNull @DefaultValue("443") Set<Integer> externalImageAllowedPorts) {

    /** 多模态开关 + 视觉模型配置的组合校验：启用即要求可用凭据。 */
    public void requireUsableWhenEnabled(com.kwiki.infrastructure.config.ExternalServicesProperties external) {
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        if (external == null || external.visionModel() == null
                || !external.visionModel().isConfigured()) {
            throw new IllegalStateException(
                    "kwiki.multimodal.enabled=true requires a configured kwiki.vision-model "
                            + "(base-url and api-key)");
        }
    }
}
