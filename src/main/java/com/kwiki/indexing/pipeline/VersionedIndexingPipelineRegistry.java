package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.chunk.ChildChunker;
import com.kwiki.indexing.chunk.ParentChunker;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.parse.DocumentParseService;
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
 * parser、chunker、embedding 客户端与维度。清单留空时唯一受支持的
 * 代是隐式默认代（kwiki.qwen-embedding 派生）。
 *
 * <p>当前部署只有一个 parser 实现（{@code kwiki-parse-1}）与一个
 * chunker 实现（{@code kwiki-chunk-1}）；其它版本号的组合不受支持。
 * 匹配是全字段精确相等；embedding 档案必须解析出 baseUrl/apiKey。
 * default 档案复用应用装配的 embedding 客户端 bean；其它档案按配置
 * 惰性构建独立客户端。凭据只存在于配置与客户端实例，绝不进入返回值
 * 或数据库。</p>
 */
@Component
public class VersionedIndexingPipelineRegistry {

    public static final String SUPPORTED_PARSER_VERSION =
            com.kwiki.indexing.job.IndexingWorker.PARSER_VERSION;
    public static final String SUPPORTED_CHUNKER_VERSION =
            com.kwiki.indexing.job.IndexingWorker.CHUNKER_VERSION;

    /** 一个受支持结构代的具体执行流水线。 */
    public record ResolvedPipeline(
            String manifestId,
            String parserVersion,
            String chunkerVersion,
            DocumentParseService parser,
            ParentChunker parentChunker,
            ChildChunker childChunker,
            ChunkEmbeddingPort embeddings,
            String embeddingModel,
            int embeddingDimensions) {
    }

    private final IndexingProperties indexingProperties;
    private final ExternalServicesProperties externalProperties;
    private final ObjectProvider<DocumentParseService> parser;
    private final ObjectProvider<ChunkEmbeddingPort> defaultEmbeddings;
    private final ObjectProvider<WebClient.Builder> webClientBuilder;
    private final ObjectProvider<com.kwiki.indexing.multimodal.MultimodalMetrics> multimodalMetrics;
    private final com.kwiki.indexing.config.MultimodalIndexingProperties multimodalProperties;
    private final Map<String, ChunkEmbeddingPort> profileClients = new ConcurrentHashMap<>();

    public VersionedIndexingPipelineRegistry(
            IndexingProperties indexingProperties,
            ExternalServicesProperties externalProperties,
            ObjectProvider<DocumentParseService> parser,
            ObjectProvider<ChunkEmbeddingPort> defaultEmbeddings,
            ObjectProvider<WebClient.Builder> webClientBuilder) {
        this(indexingProperties, externalProperties, parser, defaultEmbeddings, webClientBuilder,
                null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VersionedIndexingPipelineRegistry(
            IndexingProperties indexingProperties,
            ExternalServicesProperties externalProperties,
            ObjectProvider<DocumentParseService> parser,
            ObjectProvider<ChunkEmbeddingPort> defaultEmbeddings,
            ObjectProvider<WebClient.Builder> webClientBuilder,
            ObjectProvider<com.kwiki.indexing.multimodal.MultimodalMetrics> multimodalMetrics,
            com.kwiki.indexing.config.MultimodalIndexingProperties multimodalProperties) {
        this.indexingProperties = indexingProperties;
        this.externalProperties = externalProperties;
        this.parser = parser;
        this.defaultEmbeddings = defaultEmbeddings;
        this.webClientBuilder = webClientBuilder;
        this.multimodalMetrics = multimodalMetrics;
        this.multimodalProperties = multimodalProperties;
    }

    /** 目标 built 配置能否由当前部署执行。 */
    public boolean supports(EditableIndexConfig config) {
        return resolve(config).isPresent();
    }

    /** 多模态解析代（kwiki-parse-2）只有在功能开启时受支持。 */
    private boolean parserVersionSupported(String parserVersion) {
        if (SUPPORTED_PARSER_VERSION.equals(parserVersion)) {
            return true;
        }
        return com.kwiki.indexing.job.IndexingWorker.PARSER_VERSION_MULTIMODAL
                .equals(parserVersion)
                && multimodalProperties != null
                && Boolean.TRUE.equals(multimodalProperties.enabled());
    }

    /** 无法解析的脱敏原因（供管理端与日志使用）。 */
    public Optional<String> unsupportedReason(EditableIndexConfig config) {
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
        DocumentParseService parserInstance = parser.getIfAvailable();
        if (parserInstance == null
                || !parserVersionSupported(config.parserVersion())
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
                    manifest.dimensions()));
        }
        return Optional.empty();
    }

    /** 当前部署受支持的全部结构代（显式清单优先，否则隐式默认代）。 */
    public List<IndexingProperties.Manifest> effectiveManifests() {
        if (indexingProperties.manifests() != null && !indexingProperties.manifests().isEmpty()) {
            return indexingProperties.manifests();
        }
        var qwen = externalProperties.qwenEmbedding();
        return List.of(new IndexingProperties.Manifest(
                "implicit-default",
                SUPPORTED_PARSER_VERSION,
                SUPPORTED_CHUNKER_VERSION,
                IndexingProperties.DEFAULT_EMBEDDING_PROFILE,
                qwen.model(),
                qwen.dimensions(),
                1));
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
                && manifest.mappingSchemaVersion().intValue()
                        == config.mappingSchemaVersion().intValue();
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
        DocumentParseService parserInstance = parser.getIfAvailable();
        for (IndexingProperties.Manifest manifest : effectiveManifests()) {
            boolean runnable = parserInstance != null
                    && parserVersionSupported(manifest.parserVersion())
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
