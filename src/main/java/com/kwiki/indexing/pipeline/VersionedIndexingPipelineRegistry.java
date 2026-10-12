package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.chunk.plugin.ChunkerPlugin;
import com.kwiki.indexing.chunk.plugin.ChunkerRegistry;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.parse.plugin.DocumentParserPlugin;
import com.kwiki.indexing.parse.plugin.DocumentParserRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.infrastructure.ai.registry.EmbeddingBinding;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按目标版本"成功构建的配置修订"解析流水线（任务 5.1）：把结构配置
 * 六元组匹配到当前部署受支持的结构代清单，并给出该代对应的
 * parser、chunker、embedding 客户端与维度。清单留空时默认支持
 * 所有可用字段格式和分块器，并按解析器声明的所需能力筛选兼容组合。
 *
 * <p>解析器与分块器均由注册表提供。
 * 匹配是全字段精确相等；embedding 的「档案」即模型目录中的 provider 名，
 * 客户端由 {@link ModelRegistry} 按 provider 名 + 模型名提供，维度必须与目录登记一致。
 * 隐式组合对目录中每个可用的 embedding 模型各生成一条。凭据只存在于目录与客户端实例，
 * 绝不进入返回值。</p>
 */
@Component
public class VersionedIndexingPipelineRegistry {

    public static final String ENTITY_LINKING_VERSION = "entity-linking-v1";

    /** 一个受支持结构代的具体执行流水线。 */
    public record ResolvedPipeline(
            String manifestId,
            String parserVersion,
            String chunkerVersion,
            DocumentParserPlugin parser,
            ChunkerPlugin chunker,
            ChunkEmbeddingPort embeddings,
            String embeddingModel,
            int embeddingDimensions,
            String indexSchema,
            String entityLinkingVersion) {
    }

    private final ChunkIndexSchemaRegistry schemaRegistry;
    private final IndexingProperties indexingProperties;
    private final ModelRegistry models;
    private final DocumentParserRegistry parserRegistry;
    private final ChunkerRegistry chunkerRegistry;

    public VersionedIndexingPipelineRegistry(
            IndexingProperties indexingProperties,
            ModelRegistry models,
            DocumentParserRegistry parserRegistry,
            ChunkerRegistry chunkerRegistry,
            ChunkIndexSchemaRegistry schemaRegistry) {
        this.schemaRegistry = schemaRegistry;
        if (indexingProperties.manifests() != null) {
            for (var manifest : indexingProperties.manifests()) {
                if (schemaRegistry.find(manifest.indexSchema()).isEmpty()) {
                    throw new IllegalStateException("清单 " + manifest.id() + " 引用未注册的字段格式 " + manifest.indexSchema());
                }
            }
        }
        this.indexingProperties = indexingProperties;
        this.models = models;
        this.parserRegistry = parserRegistry;
        this.chunkerRegistry = chunkerRegistry;
    }

    /** 目标 built 配置能否由当前部署执行。 */
    public boolean supports(EditableIndexConfig config) {
        return resolve(config).isPresent();
    }

    /** 能力门禁同时要求完整流水线可执行。 */
    public boolean supportsCapability(EditableIndexConfig config, IndexCapability capability) {
        return resolve(config).isPresent() && schemaRegistry.require(config.indexSchema())
                .capabilities().contains(capability);
    }

    /**
     * 版本配置引用了未注册插件时返回原因：这类版本永久无法写入或切回，只能清理。
     * 插件已注册但暂时不可用（如缺少配置）不在此列——补齐配置即可恢复。
     */
    public Optional<String> unregisteredPluginReason(EditableIndexConfig config) {
        if (schemaRegistry.find(config.indexSchema()).isEmpty()) {
            return Optional.of("字段格式 " + config.indexSchema() + " 未注册");
        }
        if (parserRegistry.find(config.parserVersion()).isEmpty()) {
            return Optional.of("解析器 " + config.parserVersion() + " 未注册");
        }
        if (chunkerRegistry.find(config.chunkerVersion()).isEmpty()) {
            return Optional.of("分块器 " + config.chunkerVersion() + " 未注册");
        }
        return Optional.empty();
    }

    /** 无法解析的脱敏原因（供管理端与日志使用）。 */
    public Optional<String> unsupportedReason(EditableIndexConfig config) {
        var schema = schemaRegistry.find(config.indexSchema());
        if (schema.isEmpty()) return Optional.of("字段格式 " + config.indexSchema() + " 未注册");
        if (!schema.get().availability().available()) return Optional.of("字段格式 " + config.indexSchema() + " 不可用："
                + schema.get().availability().unavailableReason());
        var plugin = parserRegistry.find(config.parserVersion());
        if (plugin.isEmpty()) return Optional.of("解析器 " + config.parserVersion() + " 未注册");
        if (!plugin.get().availability().available()) return Optional.of("解析器 " + config.parserVersion() + " 不可用：" + plugin.get().availability().unavailableReason());
        // 仅列出缺失能力并固定排序，诊断只包含注册表标识及枚举名称。
        var missingCapabilities = plugin.get().requiredCapabilities().stream()
                .filter(capability -> !schema.get().capabilities().contains(capability))
                .map(IndexCapability::name).sorted().toList();
        if (!missingCapabilities.isEmpty()) {
            return Optional.of("字段格式 " + schema.get().id() + " 不支持解析器 " + plugin.get().id()
                    + " 要求的能力：" + String.join(", ", missingCapabilities));
        }
        var chunker = chunkerRegistry.find(config.chunkerVersion());
        if (chunker.isEmpty()) return Optional.of("分块器 " + config.chunkerVersion() + " 未注册");
        if (!chunker.get().availability().available()) return Optional.of("分块器 " + config.chunkerVersion() + " 不可用："
                + chunker.get().availability().unavailableReason());
        boolean providerKnown = models.embeddings().stream()
                .anyMatch(binding -> binding.providerName().equals(config.embeddingProvider()));
        if (!providerKnown) {
            return Optional.of("embedding profile is not configured: " + config.embeddingProvider());
        }
        boolean tupleKnown = effectiveManifests().stream()
                .anyMatch(manifest -> tupleMatches(manifest, config));
        if (!tupleKnown) {
            return Optional.of("configuration does not match any supported manifest");
        }
        return embeddingFor(config.embeddingProvider(), config.embeddingModel(), config.embeddingDimensions())
                .isPresent()
                ? Optional.empty()
                : Optional.of("embedding model is not configured: " + config.embeddingProvider()
                        + "/" + config.embeddingModel() + " (" + config.embeddingDimensions() + "D)");
    }

    public Optional<ResolvedPipeline> resolve(EditableIndexConfig config) {
        var schema = schemaRegistry.find(config.indexSchema());
        if (schema.isEmpty() || !schema.get().availability().available()) return Optional.empty();
        var chunker = chunkerRegistry.find(config.chunkerVersion());
        if (chunker.isEmpty() || !chunker.get().availability().available()) return Optional.empty();
        DocumentParserPlugin parserInstance = parserRegistry.find(config.parserVersion()).orElse(null);
        if (parserInstance == null || !parserInstance.availability().available()
                || !schema.get().capabilities().containsAll(parserInstance.requiredCapabilities())) {
            return Optional.empty();
        }
        for (IndexingProperties.Manifest manifest : effectiveManifests()) {
            if (!tupleMatches(manifest, config)) {
                continue;
            }
            ChunkEmbeddingPort embeddings = embeddingFor(manifest.embeddingProfile(),
                    manifest.embeddingModel(), manifest.dimensions()).map(EmbeddingBinding::port).orElse(null);
            if (embeddings == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedPipeline(
                    manifest.id(),
                    manifest.parserVersion(),
                    manifest.chunkerVersion(),
                    parserInstance,
                    chunker.get(),
                    embeddings,
                    manifest.embeddingModel(),
                    manifest.dimensions(),
                    manifest.indexSchema(),
                    schema.get().capabilities().contains(IndexCapability.ENTITY_LINKING) ? ENTITY_LINKING_VERSION : null));
        }
        return Optional.empty();
    }

    /** 显式清单优先；否则按可用解析器、分块器与字段格式能力生成默认组合。 */
    public List<IndexingProperties.Manifest> effectiveManifests() {
        if (indexingProperties.manifests() != null && !indexingProperties.manifests().isEmpty()) {
            return indexingProperties.manifests();
        }
        List<IndexingProperties.Manifest> manifests = new java.util.ArrayList<>();
        for (var schema : schemaRegistry.all()) {
            if (!schema.availability().available()) continue;
            for (var plugin : parserRegistry.all()) {
                if (!plugin.availability().available()
                        || !schema.capabilities().containsAll(plugin.requiredCapabilities())) continue;
                for (var chunker : chunkerRegistry.all()) {
                    if (!chunker.availability().available()) continue;
                    String id = "implicit-" + plugin.id() + "-" + chunker.id() + "-" + schema.id();
                    boolean primaryTaken = false;
                    for (EmbeddingBinding binding : models.embeddings()) {
                        // 第一个 default provider 的模型沿用历史 id，其余追加 @provider/model 以区分
                        boolean primary = !primaryTaken
                                && IndexingProperties.DEFAULT_EMBEDDING_PROFILE.equals(binding.providerName());
                        primaryTaken |= primary;
                        manifests.add(new IndexingProperties.Manifest(
                                primary ? id : id + "@" + binding.providerName() + "/" + binding.modelName(),
                                plugin.id(), chunker.id(), binding.providerName(), binding.modelName(),
                                binding.dimensions(), schema.id()));
                    }
                }
            }
        }
        return List.copyOf(manifests);
    }

    private static boolean tupleMatches(IndexingProperties.Manifest manifest,
                                        EditableIndexConfig config) {
        return manifest.parserVersion().equals(config.parserVersion())
                && manifest.chunkerVersion().equals(config.chunkerVersion())
                && manifest.embeddingProfile().equals(config.embeddingProvider())
                && manifest.embeddingModel().equals(config.embeddingModel())
                && manifest.dimensions().intValue() == config.embeddingDimensions().intValue()
                && manifest.indexSchema().equals(config.indexSchema());
    }

    private Optional<EmbeddingBinding> embeddingFor(String provider, String model, Integer dimensions) {
        return models.embedding(provider, model)
                .filter(binding -> dimensions != null && binding.dimensions() == dimensions);
    }

    /** 诊断用：每个受支持代的可执行性（脱敏）。 */
    public Map<String, String> supportDiagnostics() {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        for (IndexingProperties.Manifest manifest : effectiveManifests()) {
            boolean runnable = schemaRegistry.find(manifest.indexSchema())
                    .filter(schema -> schema.availability().available())
                    .filter(schema -> parserRegistry.find(manifest.parserVersion())
                            .filter(plugin -> plugin.availability().available()
                                    && schema.capabilities().containsAll(plugin.requiredCapabilities())).isPresent()).isPresent()
                    && chunkerRegistry.find(manifest.chunkerVersion())
                            .filter(chunker -> chunker.availability().available()).isPresent()
                    && embeddingFor(manifest.embeddingProfile(), manifest.embeddingModel(),
                            manifest.dimensions()).isPresent();
            diagnostics.put(manifest.id(), runnable ? "supported" : "pipeline or profile unavailable");
        }
        return diagnostics;
    }
}
