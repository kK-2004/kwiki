package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.config.MultimodalIndexingProperties;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
                                                       ExternalServicesProperties external) {
        return new VersionedIndexingPipelineRegistry(indexing, external,
                provider(DocumentParseService.forTests()),
                provider(defaultEmbeddings),
                nullProvider(), com.kwiki.indexing.schema.SchemaTestFixtures.schemas());
    }

    private static ExternalServicesProperties external(String model, int dimensions) {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("https://cc", "t", null, null,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("https://l/v1", "k", "m",
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("https://e/v1", "key", model,
                        dimensions, Duration.ofSeconds(30)),
                ExternalServicesProperties.unusedVisionModel());
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
        assertThat(resolved.get().manifestId()).isEqualTo("implicit-kwiki-parse-1-kwiki-schema-1");
        assertThat(resolved.get().embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(resolved.get().embeddingDimensions()).isEqualTo(1024);
        assertThat(resolved.get().embeddings()).isSameAs(defaultEmbeddings);
    }

    @Test
    void configuredVisionAndMultimodalSwitchAddImplicitV2Generation() {
        ExternalServicesProperties base = external("text-embedding-v4", 1024);
        ExternalServicesProperties configured = new ExternalServicesProperties(
                base.contentCenter(), base.elasticsearch(), base.answerLlm(), base.qwenEmbedding(),
                new ExternalServicesProperties.VisionModel("https://vision.example/v1", "secret",
                        "qwen3.7-flash", Duration.ofSeconds(5), Duration.ofSeconds(60), 2, 4));
        MultimodalIndexingProperties multimodal = mock(MultimodalIndexingProperties.class);
        org.mockito.Mockito.when(multimodal.enabled()).thenReturn(true);
        VersionedIndexingPipelineRegistry registry = new VersionedIndexingPipelineRegistry(
                emptyManifests(), configured, provider(DocumentParseService.forTests()),
                provider(defaultEmbeddings), nullProvider(), nullProvider(), multimodal, com.kwiki.indexing.schema.SchemaTestFixtures.schemas());

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

        // 配置档案凭据后：解析为该代的流水线，模型与维度取自清单。
        Map<String, IndexingProperties.EmbeddingProfile> profiles = Map.of(
                "qwen-v4", new IndexingProperties.EmbeddingProfile(
                        "https://embed.internal/v1", "sk-profile", Duration.ofSeconds(45)));
        var withProfile = registry(manifests(profiles,
                manifest("gen-v1", "default", "text-embedding-v4", 1024),
                manifest("gen-v2", "qwen-v4", "text-embedding-v4", 2048)),
                external("text-embedding-v4", 1024));
        withProfile.registerProfileClientForTests("qwen-v4", mock(ChunkEmbeddingPort.class));

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
                        assertThat(reason).contains("parser version is not supported"));
        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-2", "default", "text-embedding-v4", 1024, "kwiki-schema-1")))
                .hasValueSatisfying(reason ->
                        assertThat(reason).contains("chunker version is not supported"));
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
        var external = external("text-embedding-v4", 1024);
        var configured = new ExternalServicesProperties(external.contentCenter(), external.elasticsearch(), external.answerLlm(),
                external.qwenEmbedding(), new ExternalServicesProperties.VisionModel("https://vision.example/v1", "test", "vision",
                Duration.ofSeconds(5), Duration.ofSeconds(60), 2, 4));
        var multimodal = mock(MultimodalIndexingProperties.class);
        org.mockito.Mockito.when(multimodal.enabled()).thenReturn(true);
        var registry = new VersionedIndexingPipelineRegistry(emptyManifests(), configured, provider(DocumentParseService.forTests()),
                provider(defaultEmbeddings), nullProvider(), nullProvider(), multimodal,
                new com.kwiki.indexing.schema.ChunkIndexSchemaRegistry(List.of(text, unavailable, resource)));
        assertThat(registry.effectiveManifests()).extracting(IndexingProperties.Manifest::id).containsExactly(
                "implicit-kwiki-parse-1-resource-schema", "implicit-kwiki-parse-2-resource-schema", "implicit-kwiki-parse-1-text-schema");
        var config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "text-schema");
        assertThat(registry.resolve(config)).hasValueSatisfying(pipeline -> assertThat(pipeline.entityLinkingVersion()).isNull());
        assertThat(registry.supportsCapability(config, com.kwiki.indexing.plugin.IndexCapability.ENTITY_LINKING)).isFalse();
        assertThat(registry.resolve(new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "text-schema"))).isEmpty();
        assertThat(registry.resolve(new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "offline-schema"))).isEmpty();
        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, "offline-schema")))
                .contains("字段格式 offline-schema 不可用：测试不可用");
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

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private static <T> ObjectProvider<T> nullProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return null;
            }
        };
    }
}
