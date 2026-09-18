package com.kwiki.indexing.version;

/**
 * 一次构建开始时固化的不可变配置快照（写入 search_index_rebuild_run
 * 的 build_manifest JSON）。worker 只依据该快照解析/分块/嵌入，配置在
 * 构建期间再次变化不会影响已固化的 run。绝不包含凭据。
 */
public record BuildManifestSnapshot(
        int versionNumber,
        String physicalName,
        long configRevision,
        String parserVersion,
        String chunkerVersion,
        String embeddingProvider,
        String embeddingModel,
        int embeddingDimensions,
        int mappingSchemaVersion,
        String mappingHash) {
}
