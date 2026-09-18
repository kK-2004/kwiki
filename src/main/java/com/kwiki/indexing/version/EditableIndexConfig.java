package com.kwiki.indexing.version;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理员可编辑的结构配置六元组（版本号与物理名不可编辑）。凭据不在
 * 其中：embeddingProvider 只是 kwiki.indexing.embeddings 的档案名。
 */
public record EditableIndexConfig(
        @NotBlank String parserVersion,
        @NotBlank String chunkerVersion,
        @NotBlank String embeddingProvider,
        @NotBlank String embeddingModel,
        @NotNull @Min(64) @Max(2048) Integer embeddingDimensions,
        @NotNull @Min(1) Integer mappingSchemaVersion) {
}
