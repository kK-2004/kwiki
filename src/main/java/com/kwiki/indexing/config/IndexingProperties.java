package com.kwiki.indexing.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 索引版本管理的部署属性：受支持的结构配置清单、按名称区分的
 * embedding 凭据档案、重建/补齐限额、ES 容量阈值与管理写开关。
 *
 * <p>清单（manifest）是"当前部署可执行的结构配置代"：一个物理索引
 * 版本的 parser/chunker/embedding 模型/维度/mapping 组合必须精确匹配
 * 其中一项，才允许被选中、双写或重建。凭据只存在于本配置，绝不进入
 * 数据库或管理 API。未配置清单时，唯一受支持的结构即当前部署配置
 * （由 {@code kwiki.qwen-embedding.*} 派生，见
 * VersionedIndexingPipelineRegistry 的默认解析）。
 *
 * <p>embedding 档案名 {@value #DEFAULT_EMBEDDING_PROFILE} 在未显式
 * 配置时回退到 {@code kwiki.qwen-embedding.*}，使既有部署无需新增
 * 配置即可表达 v1。
 */
@ConfigurationProperties(prefix = "kwiki.indexing")
@Validated
public record IndexingProperties(
        @Valid @DefaultValue List<Manifest> manifests,
        @Valid @DefaultValue Map<String, EmbeddingProfile> embeddings,
        @Valid @NotNull @DefaultValue Rebuild rebuild,
        @Valid @NotNull @DefaultValue Catchup catchup,
        @Valid @NotNull @DefaultValue Capacity capacity,
        @Valid @NotNull @DefaultValue Management management) {

    public static final String DEFAULT_EMBEDDING_PROFILE = "default";

    /**
     * 一个受支持的结构配置代。id 仅用于日志与配置诊断；版本配置与
     * 清单的匹配按全部字段精确相等判定。
     */
    public record Manifest(
            @NotBlank String id,
            @NotBlank String parserVersion,
            @NotBlank String chunkerVersion,
            @NotBlank String embeddingProfile,
            @NotBlank String embeddingModel,
            @NotNull @Min(64) @Max(2048) Integer dimensions,
            @NotNull @Min(1) @DefaultValue("1") Integer mappingSchemaVersion) {
    }

    /**
     * 命名的 embedding 凭据档案。api-key 只在内存与配置层存在；
     * {@value #DEFAULT_EMBEDDING_PROFILE} 未显式配置时由
     * {@code kwiki.qwen-embedding.*} 提供取值。
     */
    public record EmbeddingProfile(
            @NotBlank
            @Pattern(regexp = "https?://\\S+", message = "must be an http(s) base URL")
            String baseUrl,
            @NotBlank String apiKey,
            @NotNull @DefaultValue("30s") Duration requestTimeout) {
    }

    /** 全量重建的节奏与资源上限；数值必须保守，等待运维显式放大。 */
    public record Rebuild(
            @NotNull @Min(1) @Max(500) @DefaultValue("50") Integer batchSize,
            @NotNull @Min(1) @Max(8) @DefaultValue("2") Integer maxConcurrentRuns,
            @NotNull @Min(1) @Max(200) @DefaultValue("20") Integer embeddingQps) {
    }

    /** 切换准备期间范围尾扫与事件重放的节奏。 */
    public record Catchup(
            @NotNull @Min(1) @Max(500) @DefaultValue("100") Integer batchSize,
            @NotNull @DefaultValue("30s") Duration scanInterval) {
    }

    /** ES 容量与健康预检阈值；无法确认时创建必须失败关闭。 */
    public record Capacity(
            @NotNull @Min(0) @Max(95) @DefaultValue("20") Integer minStorageHeadroomPercent,
            @NotNull @Min(1) @Max(64) @DefaultValue("8") Integer maxManagedIndices,
            @NotNull @DefaultValue("5s") Duration healthTimeout) {
    }

    /**
     * 管理端变更开关。发布初期保持 false（只读观察模式），
     * 由运维显式开启创建/重建/切换等写操作。
     */
    public record Management(
            @NotNull @DefaultValue("false") Boolean mutationsEnabled) {
    }
}
