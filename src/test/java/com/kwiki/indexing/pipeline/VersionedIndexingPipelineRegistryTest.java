package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;
import com.kwiki.testutil.StubModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 版本化流水线注册表契约（任务 5.1/5.5）：隐式默认代派生、清单精确
 * 匹配、不受支持的 parser/chunker/维度组合拒绝、default 档案与部署
 * 取值的一致性校验、命名档案凭据缺失时拒绝。
 */
class VersionedIndexingPipelineRegistryTest {

    private ChunkEmbeddingPort defaultEmbeddings;

    @BeforeEach
    void setUp() {
        defaultEmbeddings = mock(ChunkEmbeddingPort.class);
    }

    private VersionedIndexingPipelineRegistry registry(IndexingProperties indexing,
                                                       ModelRegistry external) {
        return new VersionedIndexingPipelineRegistry(indexing, external,
                com.kwiki.indexing.parse.plugin.ParserTestFixtures.tika(DocumentParseService.forTests()),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(java.util.List.of(new com.kwiki.indexing.chunk.plugin.KwikiChunkV1(
                        new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.kwiki.indexing.multimodal.MultimodalMetrics.class)))), com.kwiki.indexing.schema.SchemaTestFixtures.schemas());
    }

    /** 只有一个 default provider embedding 模型的目录（迁移前 kwiki.qwen-embedding 的形态）。 */
    private StubModelRegistry external(String model, int dimensions) {
        return StubModelRegistry.defaultEmbedding(model, dimensions, defaultEmbeddings);
    }

    private static IndexingProperties emptyManifests() {
        return new IndexingProperties(null, null, rebuild(), catchup(), capacity(),
                new IndexingProperties.Management(false));
    }

    private static IndexingProperties manifests(IndexingProperties.Manifest... entries) {
        return manifests(Map.of(), entries);
    }

    private static IndexingProperties manifests(
            Map<String, IndexingProperties.EmbeddingProfile> profiles,
            IndexingProperties.Manifest... entries) {
        return new IndexingProperties(List.of(entries), profiles, rebuild(), catchup(), capacity(),
                new IndexingProperties.Management(false));
    }

    private static IndexingProperties.Manifest manifest(String id, String profile, String model,
                                                        int dims) {
        return new IndexingProperties.Manifest(id, "kwiki-parse-1", "kwiki-chunk-1", profile,
                model, dims, "kwiki-schema-1");
    }

    private static IndexingProperties.Rebuild rebuild() {
        return new IndexingProperties.Rebuild(50, 2, 20);
    }

    private static IndexingProperties.Catchup catchup() {
        return new IndexingProperties.Catchup(100, Duration.ofSeconds(30));
    }

    private static IndexingProperties.Capacity capacity() {
        return new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5));
    }

    private static EditableIndexConfig config(String profile, String model, int dims) {
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", profile, model, dims, "kwiki-schema-1");
    }

    @Test
    void emptyManifestsYieldTheImplicitDefaultGeneration() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));

        var resolved = registry.resolve(config("default", "text-embedding-v4", 1024));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().manifestId()).isEqualTo("implicit-kwiki-parse-1-kwiki-chunk-1-kwiki-schema-1");
        assertThat(resolved.get().embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(resolved.get().embeddingDimensions()).isEqualTo(1024);
        assertThat(resolved.get().embeddings()).isSameAs(defaultEmbeddings);
    }

    @Test
    void configuredVisionAndMultimodalSwitchAddImplicitV2Generation() {
        var configured = external("text-embedding-v4", 1024);
        VersionedIndexingPipelineRegistry registry = new VersionedIndexingPipelineRegistry(
                emptyManifests(), configured, com.kwiki.indexing.parse.plugin.ParserTestFixtures.availableBoth(),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(java.util.List.of(new com.kwiki.indexing.chunk.plugin.KwikiChunkV1(
                        new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.kwiki.indexing.multimodal.MultimodalMetrics.class)))), com.kwiki.indexing.schema.SchemaTestFixtures.schemas());

        // 每个解析器与提供资源能力的注册字段格式生成一个组合。
        assertThat(registry.effectiveManifests())
                .extracting(IndexingProperties.Manifest::parserVersion, IndexingProperties.Manifest::indexSchema)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("kwiki-parse-1", "kwiki-schema-1"),
                        org.assertj.core.groups.Tuple.tuple("kwiki-parse-2", "kwiki-schema-1"));
        assertThat(registry.supports(new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1",
                "default", "text-embedding-v4", 1024, "kwiki-schema-1"))).isTrue();
        assertThat(registry.supportsCapability(new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1",
                "default", "text-embedding-v4", 1024, "kwiki-schema-1"), com.kwiki.indexing.plugin.IndexCapability.ENTITY_LINKING)).isTrue();
        assertThat(registry.supportsCapability(new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1",
                "default", "text-embedding-v4", 1024, "kwiki-schema-1"), com.kwiki.indexing.plugin.IndexCapability.ENTITY_LINKING)).isTrue();
    }

    @Test
    void defaultProfileMismatchingTheDeploymentValuesIsUnsupported() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));

        assertThat(registry.resolve(config("default", "text-embedding-v4", 2048))).isEmpty();
        assertThat(registry.unsupportedReason(config("default", "other-model", 1024)))
                .hasValueSatisfying(reason ->
                        assertThat(reason).contains("does not match any supported manifest"));
    }

    @Test
    void explicitManifestsMatchOnEveryField() {
        // 档案未配置凭据：即使清单声明了 gen-v2 也无法解析。
        var registry = registry(manifests(
                manifest("gen-v1", "default", "text-embedding-v4", 1024),
                manifest("gen-v2", "qwen-v4", "text-embedding-v4", 2048)),
                external("text-embedding-v4", 1024));

        assertThat(registry.resolve(new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1",
                "qwen-v4", "text-embedding-v4", 2048, "kwiki-schema-1"))).isEmpty();
        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-1", "qwen-v4", "text-embedding-v4", 2048, "kwiki-schema-1")))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("embedding profile is not configured"));

        // 目录登记 qwen-v4 provider 的模型后：解析为该代的流水线，模型与维度取自清单。
        var withProfile = registry(manifests(
                manifest("gen-v1", "default", "text-embedding-v4", 1024),
                manifest("gen-v2", "qwen-v4", "text-embedding-v4", 2048)),
                external("text-embedding-v4", 1024)
                        .withEmbedding("qwen-v4", "text-embedding-v4", 2048, mock(ChunkEmbeddingPort.class)));

        var resolved = withProfile.resolve(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-1", "qwen-v4", "text-embedding-v4", 2048, "kwiki-schema-1"));
        assertThat(resolved).isPresent();
        assertThat(resolved.get().manifestId()).isEqualTo("gen-v2");
        assertThat(resolved.get().embeddingDimensions()).isEqualTo(2048);
        assertThat(resolved.get().embeddingModel()).isEqualTo("text-embedding-v4");
    }

    @Test
    void unsupportedParserOrChunkerVersionsAreRejectedWithReasons() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));

        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-2",
                "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "kwiki-schema-1")))
                .hasValueSatisfying(reason ->
                        assertThat(reason).contains("解析器 kwiki-parse-2 未注册"));
        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-2", "default", "text-embedding-v4", 1024, "kwiki-schema-1")))
                .hasValueSatisfying(reason ->
                        assertThat(reason).contains("分块器 kwiki-chunk-2 未注册"));
    }

    @Test
    void unknownEmbeddingProfileIsRejectedWithSanitizedReason() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));

        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-1", "missing-profile", "text-embedding-v4", 1024, "kwiki-schema-1")))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("embedding profile is not configured: missing-profile"));
    }

    @Test
    void supportDiagnosticsListEveryGenerationWithoutSecrets() {
        var registry = registry(manifests(manifest("gen-v1", "default", "text-embedding-v4", 1024)),
                external("text-embedding-v4", 1024));

        var diagnostics = registry.supportDiagnostics();

        assertThat(diagnostics).containsEntry("gen-v1", "supported");
        assertThat(String.valueOf(diagnostics)).doesNotContain("key").doesNotContain("https://e");
    }

    @Test
    void 未注册字段格式拒绝解析且显式清单启动失败() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));
        var legacy = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "legacy-schema-2");
        assertThat(registry.resolve(legacy)).isEmpty();
        assertThat(registry.unsupportedReason(legacy)).contains("字段格式 legacy-schema-2 未注册");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry(manifests(new IndexingProperties.Manifest(
                "unknown", "kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "missing")),
                external("text-embedding-v4", 1024)))
                .isInstanceOf(IllegalStateException.class).hasMessage("清单 unknown 引用未注册的字段格式 missing");
    }

    @Test
    void 隐式组合按插件能力生成_不可用插件不参与_实体版本按能力派生() {
        var text = schema("text-schema", java.util.Set.of(), true);
        var unavailable = schema("offline-schema", java.util.Set.of(com.kwiki.indexing.plugin.IndexCapability.ENTITY_LINKING), false);
        var resource = schema("resource-schema", java.util.Set.of(com.kwiki.indexing.plugin.IndexCapability.RESOURCE_IDS), true);
        var configured = external("text-embedding-v4", 1024);
        var registry = new VersionedIndexingPipelineRegistry(emptyManifests(), configured, com.kwiki.indexing.parse.plugin.ParserTestFixtures.availableBoth(),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(java.util.List.of(new com.kwiki.indexing.chunk.plugin.KwikiChunkV1(
                        new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.kwiki.indexing.multimodal.MultimodalMetrics.class)))),
                new com.kwiki.indexing.schema.ChunkIndexSchemaRegistry(List.of(text, unavailable, resource)));
        assertThat(registry.effectiveManifests()).extracting(IndexingProperties.Manifest::id).containsExactly(
                "implicit-kwiki-parse-1-kwiki-chunk-1-resource-schema", "implicit-kwiki-parse-2-kwiki-chunk-1-resource-schema", "implicit-kwiki-parse-1-kwiki-chunk-1-text-schema");
        var config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "text-schema");
        assertThat(registry.resolve(config)).hasValueSatisfying(pipeline -> assertThat(pipeline.entityLinkingVersion()).isNull());
        assertThat(registry.supportsCapability(config, com.kwiki.indexing.plugin.IndexCapability.ENTITY_LINKING)).isFalse();
        assertThat(registry.resolve(new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "text-schema"))).isEmpty();
        assertThat(registry.resolve(new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "offline-schema"))).isEmpty();
        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "offline-schema")))
                .contains("字段格式 offline-schema 不可用：测试不可用");
    }

    @Test
    void 第三方解析器参与默认清单_能力和可用性决定是否可执行() {
        var third = new com.kwiki.indexing.parse.plugin.ParserTestFixtures.FakeParser("third-parser", java.util.Set.of("text/plain"),
                java.util.Set.of(com.kwiki.indexing.plugin.IndexCapability.RESOURCE_IDS), true);
        var offline = new com.kwiki.indexing.parse.plugin.ParserTestFixtures.FakeParser("offline-parser", java.util.Set.of(), java.util.Set.of(), false);
        var registry = new VersionedIndexingPipelineRegistry(emptyManifests(), external("model", 1024),
                new com.kwiki.indexing.parse.plugin.DocumentParserRegistry(List.of(third, offline)),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(java.util.List.of(new com.kwiki.indexing.chunk.plugin.KwikiChunkV1(
                        new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.kwiki.indexing.multimodal.MultimodalMetrics.class)))),
                new com.kwiki.indexing.schema.ChunkIndexSchemaRegistry(List.of(schema("text-schema", java.util.Set.of(), true),
                        schema("resource-schema", java.util.Set.of(com.kwiki.indexing.plugin.IndexCapability.RESOURCE_IDS), true))));
        assertThat(registry.effectiveManifests()).extracting(IndexingProperties.Manifest::id).containsExactly("implicit-third-parser-kwiki-chunk-1-resource-schema");
        var supported = new EditableIndexConfig("third-parser", "kwiki-chunk-1", "default", "model", 1024, "resource-schema");
        assertThat(registry.resolve(supported)).hasValueSatisfying(pipeline -> assertThat(pipeline.parser()).isSameAs(third));
        assertThat(registry.supportDiagnostics()).containsEntry("implicit-third-parser-kwiki-chunk-1-resource-schema", "supported");
        assertThat(registry.resolve(new EditableIndexConfig("third-parser", "kwiki-chunk-1", "default", "model", 1024, "text-schema"))).isEmpty();
        assertThat(registry.unsupportedReason(new EditableIndexConfig("third-parser", "kwiki-chunk-1", "default", "model", 1024, "text-schema")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("能力"));
        assertThat(registry.resolve(new EditableIndexConfig("offline-parser", "kwiki-chunk-1", "default", "model", 1024, "resource-schema"))).isEmpty();
    }

    @Test
    void 缺失能力诊断按名称排序且只列出未提供能力() {
        var required = java.util.Set.of(com.kwiki.indexing.plugin.IndexCapability.RESOURCE_IDS,
                com.kwiki.indexing.plugin.IndexCapability.ENTITY_LINKING);
        var parser = new com.kwiki.indexing.parse.plugin.ParserTestFixtures.FakeParser("diagnostic-parser",
                java.util.Set.of("text/plain"), required, true);
        var registry = new VersionedIndexingPipelineRegistry(emptyManifests(), external("model", 1024),
                new com.kwiki.indexing.parse.plugin.DocumentParserRegistry(List.of(parser)),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(java.util.List.of(new com.kwiki.indexing.chunk.plugin.KwikiChunkV1(
                        new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.kwiki.indexing.multimodal.MultimodalMetrics.class)))),
                new com.kwiki.indexing.schema.ChunkIndexSchemaRegistry(List.of(schema("empty-schema", java.util.Set.of(), true),
                        schema("resource-schema", java.util.Set.of(com.kwiki.indexing.plugin.IndexCapability.RESOURCE_IDS), true))));
        var empty = new EditableIndexConfig("diagnostic-parser", "kwiki-chunk-1", "default", "model", 1024, "empty-schema");
        assertThat(registry.resolve(empty)).isEmpty();
        assertThat(registry.unsupportedReason(empty)).contains(
                "字段格式 empty-schema 不支持解析器 diagnostic-parser 要求的能力：ENTITY_LINKING, RESOURCE_IDS");
        var partial = new EditableIndexConfig("diagnostic-parser", "kwiki-chunk-1", "default", "model", 1024, "resource-schema");
        assertThat(registry.unsupportedReason(partial)).contains(
                "字段格式 resource-schema 不支持解析器 diagnostic-parser 要求的能力：ENTITY_LINKING");
    }

    @Test
    void 未注册分块器的配置不受支持() {
        var registry = registry(emptyManifests(), external("model", 1024));
        var config = new EditableIndexConfig("kwiki-parse-1", "missing-chunker", "default", "model", 1024, "kwiki-schema-1");
        assertThat(registry.resolve(config)).isEmpty();
        assertThat(registry.unsupportedReason(config)).contains("分块器 missing-chunker 未注册");
    }

    @Test
    void 第三方分块器参与默认组合且不可用分块器拒绝解析和诊断() {
        var third = chunker("third-chunker", true);
        var second = chunker("second-chunker", true);
        var offline = chunker("offline-chunker", false);
        var registry = new VersionedIndexingPipelineRegistry(emptyManifests(), external("model", 1024),
                com.kwiki.indexing.parse.plugin.ParserTestFixtures.tika(DocumentParseService.forTests()),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(List.of(third, second, offline)), com.kwiki.indexing.schema.SchemaTestFixtures.schemas());
        assertThat(registry.effectiveManifests()).extracting(IndexingProperties.Manifest::chunkerVersion).containsExactly("second-chunker", "third-chunker");
        var config = new EditableIndexConfig("kwiki-parse-1", "third-chunker", "default", "model", 1024, "kwiki-schema-1");
        assertThat(registry.resolve(config)).hasValueSatisfying(pipeline -> assertThat(pipeline.chunker()).isSameAs(third));
        assertThat(registry.unsupportedReason(config)).isEmpty();
        assertThat(registry.supportDiagnostics().values()).containsExactly("supported", "supported");
        var unavailable = new EditableIndexConfig("kwiki-parse-1", "offline-chunker", "default", "model", 1024, "kwiki-schema-1");
        assertThat(registry.resolve(unavailable)).isEmpty();
        assertThat(registry.unsupportedReason(unavailable)).contains("分块器 offline-chunker 不可用：测试不可用");
        var explicit = new VersionedIndexingPipelineRegistry(manifests(new IndexingProperties.Manifest(
                "offline", "kwiki-parse-1", "offline-chunker", "default", "model", 1024, "kwiki-schema-1")), external("model", 1024),
                com.kwiki.indexing.parse.plugin.ParserTestFixtures.tika(DocumentParseService.forTests()),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(List.of(offline)),
                com.kwiki.indexing.schema.SchemaTestFixtures.schemas());
        assertThat(explicit.resolve(unavailable)).isEmpty();
        assertThat(explicit.supportDiagnostics()).containsEntry("offline", "pipeline or profile unavailable");
        var unknown = new VersionedIndexingPipelineRegistry(manifests(new IndexingProperties.Manifest(
                "unknown", "kwiki-parse-1", "missing-chunker", "default", "model", 1024, "kwiki-schema-1")), external("model", 1024),
                com.kwiki.indexing.parse.plugin.ParserTestFixtures.tika(DocumentParseService.forTests()),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(List.of(third)),
                com.kwiki.indexing.schema.SchemaTestFixtures.schemas());
        assertThat(unknown.supportDiagnostics()).containsEntry("unknown", "pipeline or profile unavailable");
    }

    private static com.kwiki.indexing.chunk.plugin.ChunkerPlugin chunker(String id, boolean available) {
        var chunker = mock(com.kwiki.indexing.chunk.plugin.ChunkerPlugin.class);
        org.mockito.Mockito.when(chunker.id()).thenReturn(id);
        org.mockito.Mockito.when(chunker.availability()).thenReturn(available ? com.kwiki.indexing.plugin.PluginAvailability.ok()
                : com.kwiki.indexing.plugin.PluginAvailability.unavailable("测试不可用"));
        return chunker;
    }

    private static com.kwiki.indexing.schema.ChunkIndexSchema schema(String id,
            java.util.Set<com.kwiki.indexing.plugin.IndexCapability> capabilities, boolean available) {
        var schema = mock(com.kwiki.indexing.schema.ChunkIndexSchema.class);
        org.mockito.Mockito.when(schema.id()).thenReturn(id);
        org.mockito.Mockito.when(schema.capabilities()).thenReturn(capabilities);
        org.mockito.Mockito.when(schema.availability()).thenReturn(available ? com.kwiki.indexing.plugin.PluginAvailability.ok()
                : com.kwiki.indexing.plugin.PluginAvailability.unavailable("测试不可用"));
        return schema;
    }
}
