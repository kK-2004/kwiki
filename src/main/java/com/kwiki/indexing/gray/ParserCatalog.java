package com.kwiki.indexing.gray;

import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.wiki.api.ConflictException;
import org.springframework.stereotype.Component;

import java.util.List;
import com.kwiki.indexing.parse.plugin.DocumentParserRegistry;

/** 当前部署可用的解析器目录。由插件注册表提供。 */
@Component
public class ParserCatalog {

    private final DocumentParserRegistry registry;
    private final com.kwiki.indexing.config.IndexingDefaultsProperties defaults;
    /** 流水线注册表与版本注册表；为 null（离线测试）时不做结构清单匹配。 */
    private final com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry pipelines;
    private final com.kwiki.indexing.version.SearchIndexVersionRepository versions;

    public ParserCatalog(DocumentParserRegistry registry) {
        this(registry, null, null, new com.kwiki.indexing.config.IndexingDefaultsProperties("kwiki-schema-1"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ParserCatalog(DocumentParserRegistry registry,
                         @org.springframework.lang.Nullable com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry pipelines,
                         @org.springframework.lang.Nullable com.kwiki.indexing.version.SearchIndexVersionRepository versions,
                         com.kwiki.indexing.config.IndexingDefaultsProperties defaults) {
        this.registry = registry;
        this.defaults = defaults;
        this.pipelines = pipelines;
        this.versions = versions;
    }

    /**
     * 灰度配置保持全局版本的向量模型与维度；优先基准字段格式，其次默认字段格式。
     * 候选清单必须能够解析出完整可执行流水线。
     */
    public java.util.Optional<com.kwiki.indexing.version.EditableIndexConfig> configFor(
            String parserVersion, com.kwiki.indexing.version.EditableIndexConfig base) {
        if (pipelines == null) {
            return java.util.Optional.of(new com.kwiki.indexing.version.EditableIndexConfig(parserVersion,
                    base.chunkerVersion(), base.embeddingProvider(), base.embeddingModel(),
                    base.embeddingDimensions(), base.indexSchema()));
        }
        return pipelines.effectiveManifests().stream()
                .filter(manifest -> manifest.parserVersion().equals(parserVersion)
                        && manifest.embeddingModel().equals(base.embeddingModel())
                        && manifest.dimensions().intValue() == base.embeddingDimensions().intValue())
                // 保留基准字段格式，其次优先默认字段格式。
                .sorted(java.util.Comparator.<com.kwiki.indexing.config.IndexingProperties.Manifest>comparingInt(
                                manifest -> manifest.indexSchema().equals(base.indexSchema()) ? 0 : 1)
                        .thenComparingInt(manifest -> manifest.indexSchema().equals(defaults.indexSchema()) ? 0 : 1))
                .map(manifest -> new com.kwiki.indexing.version.EditableIndexConfig(parserVersion,
                        manifest.chunkerVersion(), manifest.embeddingProfile(), manifest.embeddingModel(),
                        manifest.dimensions(), manifest.indexSchema()))
                .filter(pipelines::supports)
                .findFirst();
    }

    /** 首次部署固定默认分块器、字段格式和向量配置，只切换偏好解析器。 */
    public java.util.Optional<com.kwiki.indexing.version.EditableIndexConfig> deploymentConfigFor(
            String parserVersion, com.kwiki.indexing.version.EditableIndexConfig base) {
        var candidate = new com.kwiki.indexing.version.EditableIndexConfig(parserVersion,
                base.chunkerVersion(), base.embeddingProvider(), base.embeddingModel(),
                base.embeddingDimensions(), base.indexSchema());
        return pipelines == null || pipelines.supports(candidate)
                ? java.util.Optional.of(candidate) : java.util.Optional.empty();
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

    public record ParserOption(String id, String label, boolean available, String unavailableReason,
                               List<String> requiredCapabilities) { }

    public List<IndexCapability> requiredCapabilities(String parserVersion) {
        return registry.find(parserVersion).map(plugin -> plugin.requiredCapabilities().stream().sorted().toList())
                .orElse(List.of());
    }

    private List<String> capabilityNames(String parserVersion) {
        return requiredCapabilities(parserVersion).stream().map(Enum::name).toList();
    }

    public String label(String parserVersion) {
        return registry.find(parserVersion).map(plugin -> plugin.label()).orElse(parserVersion);
    }

    public List<ParserOption> options() {
        return registry.all().stream().map(plugin -> {
            var availability = plugin.availability();
            String problem = availability.available() ? manifestProblem(plugin.id())
                    : availability.unavailableReason();
            return new ParserOption(plugin.id(), plugin.label(), availability.available() && problem == null, problem,
                    capabilityNames(plugin.id()));
        }).toList();
    }

    public void requireAvailable(String parserVersion) {
        ParserOption option = options().stream().filter(item -> item.id().equals(parserVersion)).findFirst()
                .orElseThrow(() -> new ConflictException("未知的解析器：" + parserVersion));
        if (!option.available()) {
            throw new ConflictException("解析器 " + option.label() + " 暂不可用，" + option.unavailableReason());
        }
    }
}
