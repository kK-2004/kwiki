package com.kwiki.indexing.gray;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.wiki.api.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserCatalogTest {

    @Test
    void 列出两个解析器并给出显示名与可用性() {
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(readiness.ready()).thenReturn(false);
        when(readiness.missingConfiguration()).thenReturn(List.of("kwiki.indexing.multimodal.vision.base-url"));
        ParserCatalog catalog = new ParserCatalog(readiness);

        assertThat(catalog.options()).extracting(ParserCatalog.ParserOption::id)
                .containsExactly("kwiki-parse-1", "kwiki-parse-2");
        assertThat(catalog.options().get(0).label()).isEqualTo("tika-v1");
        assertThat(catalog.options().get(0).available()).isTrue();
        assertThat(catalog.options().get(1).label()).isEqualTo("pdfbox-v2");
        assertThat(catalog.options().get(1).available()).isFalse();
        assertThat(catalog.options().get(1).unavailableReason()).contains("base-url");
    }

    @Test
    void 不可用或未知解析器被拒绝() {
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(readiness.ready()).thenReturn(false);
        when(readiness.missingConfiguration()).thenReturn(List.of("x"));
        ParserCatalog catalog = new ParserCatalog(readiness);
        assertThatThrownBy(() -> catalog.requireAvailable("kwiki-parse-2")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> catalog.requireAvailable("nope")).isInstanceOf(ConflictException.class);
        catalog.requireAvailable("kwiki-parse-1");
        assertThat(ParserCatalog.label("kwiki-parse-2")).isEqualTo("pdfbox-v2");
        assertThat(ParserCatalog.label("custom")).isEqualTo("custom");
    }

    private static com.kwiki.indexing.config.IndexingProperties.Manifest manifest(
            String parser, String model, int dims, int schema) {
        return new com.kwiki.indexing.config.IndexingProperties.Manifest(
                parser + "-" + schema, parser, "kwiki-chunk-1", "default", model, dims, schema);
    }

    private static ParserCatalog catalogWith(List<com.kwiki.indexing.config.IndexingProperties.Manifest> manifests,
                                             com.kwiki.indexing.version.EditableIndexConfig selected) {
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(readiness.ready()).thenReturn(true);
        com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry pipelines =
                mock(com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry.class);
        when(pipelines.effectiveManifests()).thenReturn(manifests);
        when(pipelines.supports(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        com.kwiki.indexing.version.SearchIndexVersionRepository versions =
                mock(com.kwiki.indexing.version.SearchIndexVersionRepository.class);
        com.kwiki.indexing.version.SearchIndexVersion version = mock(com.kwiki.indexing.version.SearchIndexVersion.class);
        when(version.editableConfig()).thenReturn(selected);
        when(versions.findBySelectedTrue()).thenReturn(java.util.Optional.of(version));
        return new ParserCatalog(readiness, pipelines, versions);
    }

    @Test
    void 新部署取解析器受支持的最高结构版本_灰度仍优先与全局结构版本一致() {
        var base = new com.kwiki.indexing.version.EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, 1);
        ParserCatalog catalog = catalogWith(List.of(manifest("kwiki-parse-1", "qwen", 1024, 1),
                manifest("kwiki-parse-1", "qwen", 1024, 3), manifest("kwiki-parse-2", "qwen", 1024, 2),
                manifest("kwiki-parse-2", "qwen", 1024, 3), manifest("kwiki-parse-2", "other", 768, 3)), base);

        assertThat(catalog.latestConfigFor("kwiki-parse-1", base).orElseThrow().mappingSchemaVersion()).isEqualTo(3);
        var latest = catalog.latestConfigFor("kwiki-parse-2", base).orElseThrow();
        assertThat(latest.mappingSchemaVersion()).isEqualTo(3);
        assertThat(latest.embeddingModel()).isEqualTo("qwen");
        // 灰度：全局为结构 1 时 parse-2 没有结构 1，退回最高的 3
        assertThat(catalog.configFor("kwiki-parse-1", base).orElseThrow().mappingSchemaVersion()).isEqualTo(1);
    }

    @Test
    void 灰度配置取目标解析器的受支持清单_而不是沿用全局的结构版本() {
        var base = new com.kwiki.indexing.version.EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, 1);
        ParserCatalog catalog = catalogWith(List.of(manifest("kwiki-parse-1", "qwen", 1024, 1),
                manifest("kwiki-parse-2", "qwen", 1024, 2)), base);

        var config = catalog.configFor("kwiki-parse-2", base).orElseThrow();

        assertThat(config.parserVersion()).isEqualTo("kwiki-parse-2");
        assertThat(config.mappingSchemaVersion()).isEqualTo(2);
        assertThat(config.embeddingModel()).isEqualTo("qwen");
        assertThat(config.embeddingDimensions()).isEqualTo(1024);
    }

    @Test
    void 没有与全局向量配置兼容的清单时解析器不可用并说明原因() {
        var base = new com.kwiki.indexing.version.EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, 1);
        ParserCatalog catalog = catalogWith(List.of(manifest("kwiki-parse-1", "qwen", 1024, 1),
                manifest("kwiki-parse-2", "other-model", 768, 2)), base);

        assertThat(catalog.configFor("kwiki-parse-2", base)).isEmpty();
        var option = catalog.options().stream().filter(item -> item.id().equals("kwiki-parse-2")).findFirst().orElseThrow();
        assertThat(option.available()).isFalse();
        assertThat(option.unavailableReason()).contains("qwen").contains("1024").contains("kwiki.indexing.manifests");
        assertThatThrownBy(() -> catalog.requireAvailable("kwiki-parse-2")).isInstanceOf(ConflictException.class);
    }
}
