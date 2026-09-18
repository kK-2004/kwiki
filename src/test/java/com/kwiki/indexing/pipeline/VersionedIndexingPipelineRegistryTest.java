package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.config.IndexingProperties;
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
                nullProvider());
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
                model, dims, 1);
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
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", profile, model, dims, 1);
    }

    @Test
    void emptyManifestsYieldTheImplicitDefaultGeneration() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));

        var resolved = registry.resolve(config("default", "text-embedding-v4", 1024));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().manifestId()).isEqualTo("implicit-default");
        assertThat(resolved.get().embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(resolved.get().embeddingDimensions()).isEqualTo(1024);
        assertThat(resolved.get().embeddings()).isSameAs(defaultEmbeddings);
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
                "qwen-v4", "text-embedding-v4", 2048, 1))).isEmpty();
        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-1", "qwen-v4", "text-embedding-v4", 2048, 1)))
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
                "kwiki-chunk-1", "qwen-v4", "text-embedding-v4", 2048, 1));
        assertThat(resolved).isPresent();
        assertThat(resolved.get().manifestId()).isEqualTo("gen-v2");
        assertThat(resolved.get().embeddingDimensions()).isEqualTo(2048);
        assertThat(resolved.get().embeddingModel()).isEqualTo("text-embedding-v4");
    }

    @Test
    void unsupportedParserOrChunkerVersionsAreRejectedWithReasons() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));

        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-2",
                "kwiki-chunk-1", "default", "text-embedding-v4", 1024, 1)))
                .hasValueSatisfying(reason ->
                        assertThat(reason).contains("parser version is not supported"));
        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-2", "default", "text-embedding-v4", 1024, 1)))
                .hasValueSatisfying(reason ->
                        assertThat(reason).contains("chunker version is not supported"));
    }

    @Test
    void unknownEmbeddingProfileIsRejectedWithSanitizedReason() {
        var registry = registry(emptyManifests(), external("text-embedding-v4", 1024));

        assertThat(registry.unsupportedReason(new EditableIndexConfig("kwiki-parse-1",
                "kwiki-chunk-1", "missing-profile", "text-embedding-v4", 1024, 1)))
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
