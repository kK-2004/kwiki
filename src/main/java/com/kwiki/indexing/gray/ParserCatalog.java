package com.kwiki.indexing.gray;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.job.IndexingWorker;
import com.kwiki.wiki.api.ConflictException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 当前部署可用的解析器目录。新增解析器时在此登记（二期改为注册表）。 */
@Component
public class ParserCatalog {

    private static final Map<String, String> LABELS = Map.of(
            IndexingWorker.PARSER_VERSION, "tika-v1",
            IndexingWorker.PARSER_VERSION_MULTIMODAL, "pdfbox-v2");

    private final MultimodalSwitchReadiness readiness;
    /** 流水线注册表与版本注册表；为 null（离线测试）时不做结构清单匹配。 */
    private final com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry pipelines;
    private final com.kwiki.indexing.version.SearchIndexVersionRepository versions;

    public ParserCatalog(MultimodalSwitchReadiness readiness) {
        this(readiness, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ParserCatalog(MultimodalSwitchReadiness readiness,
                         @org.springframework.lang.Nullable com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry pipelines,
                         @org.springframework.lang.Nullable com.kwiki.indexing.version.SearchIndexVersionRepository versions) {
        this.readiness = readiness;
        this.pipelines = pipelines;
        this.versions = versions;
    }

    /**
     * 为灰度生成索引配置：在受支持的结构清单中找「目标解析器 + 与全局版本相同的向量模型与维度」的那一条，
     * 取其分块器、向量档案与结构版本。不能沿用全局版本的结构版本——不同解析代登记的结构版本可能不同
     * （如隐式清单中 parse-1 为 1、parse-2 为 2），原样复制会得到任何清单都不匹配的组合。
     * 向量模型与维度必须与全局一致，否则灰度索引与全局索引的查询向量不兼容。
     */
    public java.util.Optional<com.kwiki.indexing.version.EditableIndexConfig> configFor(
            String parserVersion, com.kwiki.indexing.version.EditableIndexConfig base) {
        return configFor(parserVersion, base, true);
    }

    /**
     * 新部署用：同样要求向量模型与维度一致，但直接取该解析器受支持的最高结构版本
     * （默认即含实体映射字段的 v3，可用于图构建），不向基准版本的结构版本靠拢。
     */
    public java.util.Optional<com.kwiki.indexing.version.EditableIndexConfig> latestConfigFor(
            String parserVersion, com.kwiki.indexing.version.EditableIndexConfig base) {
        return configFor(parserVersion, base, false);
    }

    private java.util.Optional<com.kwiki.indexing.version.EditableIndexConfig> configFor(
            String parserVersion, com.kwiki.indexing.version.EditableIndexConfig base,
            boolean preferBaseSchema) {
        if (pipelines == null) {
            return java.util.Optional.of(new com.kwiki.indexing.version.EditableIndexConfig(parserVersion,
                    base.chunkerVersion(), base.embeddingProvider(), base.embeddingModel(),
                    base.embeddingDimensions(), base.mappingSchemaVersion()));
        }
        return pipelines.effectiveManifests().stream()
                .filter(manifest -> manifest.parserVersion().equals(parserVersion)
                        && manifest.embeddingModel().equals(base.embeddingModel())
                        && manifest.dimensions().intValue() == base.embeddingDimensions().intValue())
                // 与全局结构版本相同的清单优先，其次取结构版本最高的
                .sorted(java.util.Comparator.<com.kwiki.indexing.config.IndexingProperties.Manifest>comparingInt(
                                manifest -> preferBaseSchema && manifest.mappingSchemaVersion().intValue()
                                        == base.mappingSchemaVersion().intValue() ? 0 : 1)
                        .thenComparing(manifest -> -manifest.mappingSchemaVersion()))
                .map(manifest -> new com.kwiki.indexing.version.EditableIndexConfig(parserVersion,
                        manifest.chunkerVersion(), manifest.embeddingProfile(), manifest.embeddingModel(),
                        manifest.dimensions(), manifest.mappingSchemaVersion()))
                .filter(pipelines::supports)
                .findFirst();
    }

    /** 当前全局已发布版本的配置；没有时返回 null。 */
    private com.kwiki.indexing.version.EditableIndexConfig selectedConfig() {
        return versions == null ? null
                : versions.findBySelectedTrue().map(com.kwiki.indexing.version.SearchIndexVersion::editableConfig)
                        .orElse(null);
    }

    /** 该解析器在当前部署下无法用于灰度的原因；可用时返回 null。 */
    private String manifestProblem(String parserVersion) {
        com.kwiki.indexing.version.EditableIndexConfig base = selectedConfig();
        if (pipelines == null || base == null || configFor(parserVersion, base).isPresent()) {
            return null;
        }
        return "未登记与当前全局版本（模型 " + base.embeddingModel() + "，" + base.embeddingDimensions()
                + " 维）兼容的索引结构清单，请在 kwiki.indexing.manifests 中登记";
    }

    public record ParserOption(String id, String label, boolean available, String unavailableReason) { }

    public static String label(String parserVersion) {
        return LABELS.getOrDefault(parserVersion, parserVersion);
    }

    public List<ParserOption> options() {
        String tikaProblem = manifestProblem(IndexingWorker.PARSER_VERSION);
        String multimodalProblem = readiness.ready()
                ? manifestProblem(IndexingWorker.PARSER_VERSION_MULTIMODAL)
                : "缺少配置：" + String.join("、", readiness.missingConfiguration());
        return List.of(
                new ParserOption(IndexingWorker.PARSER_VERSION, label(IndexingWorker.PARSER_VERSION),
                        tikaProblem == null, tikaProblem),
                new ParserOption(IndexingWorker.PARSER_VERSION_MULTIMODAL,
                        label(IndexingWorker.PARSER_VERSION_MULTIMODAL), multimodalProblem == null, multimodalProblem));
    }

    public void requireAvailable(String parserVersion) {
        ParserOption option = options().stream().filter(item -> item.id().equals(parserVersion)).findFirst()
                .orElseThrow(() -> new ConflictException("未知的解析器：" + parserVersion));
        if (!option.available()) {
            throw new ConflictException("解析器 " + option.label() + " 暂不可用，" + option.unavailableReason());
        }
    }
}
