package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.chunk.ChildChunker;
import com.kwiki.indexing.chunk.ParentChunker;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.parse.plugin.DocumentParserPlugin;
import com.kwiki.indexing.parse.plugin.DocumentParserRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按目标版本"成功构建的配置修订"解析流水线（任务 5.1）：把结构配置
 * 六元组匹配到当前部署受支持的结构代清单，并给出该代对应的
 * parser、chunker、embedding 客户端与维度。清单留空时默认支持
 * 所有可用字段格式，并按解析器声明的所需能力筛选兼容组合。
 *
 * <p>解析器由注册表提供，chunker 为 {@code kwiki-chunk-1}。
 * 匹配是全字段精确相等；embedding 档案必须解析出 baseUrl/apiKey。
 * default 档案复用应用装配的 embedding 客户端 bean；其它档案按配置
 * 惰性构建独立客户端。凭据只存在于配置与客户端实例，绝不进入返回值
 * 或数据库。</p>
 */
@Component
public class VersionedIndexingPipelineRegistry {

    public static final String SUPPORTED_CHUNKER_VERSION =
            com.kwiki.indexing.job.IndexingWorker.CHUNKER_VERSION;
    public static final String ENTITY_LINKING_VERSION = "entity-linking-v1";

    /** 一个受支持结构代的具体执行流水线。 */
    public record ResolvedPipeline(
            String manifestId,
            String parserVersion,
            String chunkerVersion,
            DocumentParserPlugin parser,
            ParentChunker parentChunker,
            ChildChunker childChunker,
            ChunkEmbeddingPort embeddings,
            String embeddingModel,
            int embeddingDimensions,
            String indexSchema,
            String entityLinkingVersion) {
    }

    private final ChunkIndexSchemaRegistry schemaRegistry;
    private final IndexingProperties indexingProperties;
    private final ExternalServicesProperties externalProperties;
    private final DocumentParserRegistry parserRegistry;
    private final ObjectProvider<ChunkEmbeddingPort> defaultEmbeddings;
    private final ObjectProvider<WebClient.Builder> webClientBuilder;
    private final ObjectProvider<com.kwiki.indexing.multimodal.MultimodalMetrics> multimodalMetrics;
    private final Map<String, ChunkEmbeddingPort> profileClients = new ConcurrentHashMap<>();

    public VersionedIndexingPipelineRegistry(
            IndexingProperties indexingProperties,
            ExternalServicesProperties externalProperties,
            DocumentParserRegistry parserRegistry,
            ObjectProvider<ChunkEmbeddingPort> defaultEmbeddings,
            ObjectProvider<WebClient.Builder> webClientBuilder, ChunkIndexSchemaRegistry schemaRegistry) {
        this(indexingProperties, externalProperties, parserRegistry, defaultEmbeddings, webClientBuilder,
                null, schemaRegistry);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VersionedIndexingPipelineRegistry(
            IndexingProperties indexingProperties,
            ExternalServicesProperties externalProperties,
            DocumentParserRegistry parserRegistry,
            ObjectProvider<ChunkEmbeddingPort> defaultEmbeddings,
            ObjectProvider<WebClient.Builder> webClientBuilder,
            ObjectProvider<com.kwiki.indexing.multimodal.MultimodalMetrics> multimodalMetrics,
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
        this.externalProperties = externalProperties;
        this.parserRegistry = parserRegistry;
        this.defaultEmbeddings = defaultEmbeddings;
        this.webClientBuilder = webClientBuilder;
        this.multimodalMetrics = multimodalMetrics;
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

    /** 注册且可用的解析器才可执行。 */
    private boolean parserVersionSupported(String parserVersion) {
        return parserRegistry.find(parserVersion).filter(plugin -> plugin.availability().available()).isPresent();
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
        if (!parserVersionSupported(config.parserVersion())) {
            return Optional.of("parser version is not supported by this deployment: "
                    + config.parserVersion());
        }
        if (!SUPPORTED_CHUNKER_VERSION.equals(config.chunkerVersion())) {
            return Optional.of("chunker version is not supported by this deployment: "
                    + config.chunkerVersion());
        }
        boolean tupleKnown = effectiveManifests().stream()
                .anyMatch(manifest -> tupleMatches(manifest, config));
        if (!tupleKnown) {
            return Optional.of("configuration does not match any supported manifest");
        }
        return embeddingProfileOf(config).isPresent()
                ? Optional.empty()
                : Optional.of("embedding profile is not configured: " + config.embeddingProvider());
    }

    public Optional<ResolvedPipeline> resolve(EditableIndexConfig config) {
        var schema = schemaRegistry.find(config.indexSchema());
        if (schema.isEmpty() || !schema.get().availability().available()) return Optional.empty();
        DocumentParserPlugin parserInstance = parserRegistry.find(config.parserVersion()).orElse(null);
        if (parserInstance == null || !parserInstance.availability().available()
                || !schema.get().capabilities().containsAll(parserInstance.requiredCapabilities())
                || !SUPPORTED_CHUNKER_VERSION.equals(config.chunkerVersion())) {
            return Optional.empty();
        }
        for (IndexingProperties.Manifest manifest : effectiveManifests()) {
            if (!tupleMatches(manifest, config)) {
                continue;
            }
            ChunkEmbeddingPort embeddings = embeddingClient(manifest, config);
            if (embeddings == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedPipeline(
                    manifest.id(),
                    manifest.parserVersion(),
                    manifest.chunkerVersion(),
                    parserInstance,
                    new ParentChunker(com.kwiki.indexing.chunk.ChunkingConfig.defaults(),
                            multimodalMetrics()),
                    new ChildChunker(com.kwiki.indexing.chunk.ChunkingConfig.defaults(),
                            multimodalMetrics()),
                    embeddings,
                    manifest.embeddingModel(),
                    manifest.dimensions(),
                    manifest.indexSchema(),
                    schema.get().capabilities().contains(IndexCapability.ENTITY_LINKING) ? ENTITY_LINKING_VERSION : null));
        }
        return Optional.empty();
    }

    /** 显式清单优先；否则按可用解析器与字段格式能力生成默认组合。 */
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
                manifests.add(implicitManifest("implicit-" + plugin.id() + "-" + schema.id(), plugin.id(), schema.id()));
            }
        }
        return List.copyOf(manifests);
    }

    private IndexingProperties.Manifest implicitManifest(String id, String parserVersion,
                                                         String indexSchema) {
        var qwen = externalProperties.qwenEmbedding();
        return new IndexingProperties.Manifest(id, parserVersion, SUPPORTED_CHUNKER_VERSION,
                IndexingProperties.DEFAULT_EMBEDDING_PROFILE, qwen.model(), qwen.dimensions(),
                indexSchema);
    }

    /** 多模态指标（未启用时为 null，计数为空操作）。 */
    private com.kwiki.indexing.multimodal.MultimodalMetrics multimodalMetrics() {
        return multimodalMetrics == null ? null : multimodalMetrics.getIfAvailable();
    }

    private static boolean tupleMatches(IndexingProperties.Manifest manifest,
                                        EditableIndexConfig config) {
        return manifest.parserVersion().equals(config.parserVersion())
                && manifest.chunkerVersion().equals(config.chunkerVersion())
                && manifest.embeddingModel().equals(config.embeddingModel())
                && manifest.dimensions().intValue() == config.embeddingDimensions().intValue()
                && manifest.indexSchema().equals(config.indexSchema());
    }

    private Optional<IndexingProperties.EmbeddingProfile> embeddingProfileOf(
            EditableIndexConfig config) {
        Map<String, IndexingProperties.EmbeddingProfile> profiles = indexingProperties.embeddings();
        if (profiles != null && profiles.containsKey(config.embeddingProvider())) {
            return Optional.of(profiles.get(config.embeddingProvider()));
        }
        if (IndexingProperties.DEFAULT_EMBEDDING_PROFILE.equals(config.embeddingProvider())) {
            return Optional.of(new IndexingProperties.EmbeddingProfile(
                    externalProperties.qwenEmbedding().baseUrl(),
                    externalProperties.qwenEmbedding().apiKey(),
                    externalProperties.qwenEmbedding().requestTimeout()));
        }
        return Optional.empty();
    }

    private ChunkEmbeddingPort embeddingClient(IndexingProperties.Manifest manifest,
                                               EditableIndexConfig config) {
        if (IndexingProperties.DEFAULT_EMBEDDING_PROFILE.equals(manifest.embeddingProfile())) {
            var qwen = externalProperties.qwenEmbedding();
            if (!qwen.model().equals(config.embeddingModel())
                    || qwen.dimensions().intValue() != config.embeddingDimensions().intValue()) {
                // default 档案的部署取值与请求代不一致：该代不受支持。
                return null;
            }
            return defaultEmbeddings.getIfAvailable();
        }
        return embeddingProfileOf(config)
                .map(profile -> profileClients.computeIfAbsent(
                        manifest.embeddingProfile(), key -> buildClient(profile, manifest)))
                .orElse(null);
    }

    private ChunkEmbeddingPort buildClient(IndexingProperties.EmbeddingProfile profile,
                                           IndexingProperties.Manifest manifest) {
        WebClient.Builder builder = webClientBuilder.getIfAvailable();
        if (builder == null) {
            throw new IllegalStateException(
                    "no WebClient builder available for embedding profile");
        }
        WebClient client = builder
                .baseUrl(profile.baseUrl())
                .defaultHeader("Authorization", "Bearer " + profile.apiKey())
                .build();
        return new com.kwiki.infrastructure.ai.QwenEmbeddingClient(
                client, manifest.embeddingModel(), manifest.dimensions(),
                defaultEmbeddingBatchSize(), defaultEmbeddingMaxRetries());
    }

    private int defaultEmbeddingBatchSize() {
        return 25;
    }

    private int defaultEmbeddingMaxRetries() {
        return 2;
    }

    /** 供测试注入档案客户端。 */
    public void registerProfileClientForTests(String profile, ChunkEmbeddingPort client) {
        profileClients.put(profile, client);
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
                    && SUPPORTED_CHUNKER_VERSION.equals(manifest.chunkerVersion())
                    && (IndexingProperties.DEFAULT_EMBEDDING_PROFILE
                            .equals(manifest.embeddingProfile())
                        ? defaultEmbeddings.getIfAvailable() != null
                        : indexingProperties.embeddings() != null
                                && indexingProperties.embeddings()
                                        .containsKey(manifest.embeddingProfile()));
            diagnostics.put(manifest.id(), runnable ? "supported" : "pipeline or profile unavailable");
        }
        return diagnostics;
    }
}
