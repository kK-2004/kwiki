package com.kwiki.graph.persistence;

import com.kwiki.graph.plugin.CommunityDetectorRegistry;
import com.kwiki.graph.plugin.CommunityDetectorPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.graph.plugin.CommunitySummarizerPlugin;
import com.kwiki.graph.plugin.GraphExtractorRegistry;
import com.kwiki.graph.plugin.GraphExtractorPlugin;
import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.CommunitySummaryInput;
import com.kwiki.graph.GraphExtractionInput;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.plugin.PluginAvailability;
import com.kwiki.indexing.schema.SchemaTestFixtures;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GraphBuildBatchServiceTest {
    private final GraphBuildRepository repository = mock(GraphBuildRepository.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final VersionedIndexingPipelineRegistry pipelines = mock(VersionedIndexingPipelineRegistry.class);
    private final GraphExtractorRegistry extractors = new GraphExtractorRegistry(
            List.of(stubExtractor("kwiki-extractor-1", PluginAvailability.ok())));
    private final CommunityDetectorRegistry detectors = new CommunityDetectorRegistry(
            List.of(stubDetector("arcadedb-leiden-1", PluginAvailability.ok())));
    private final CommunitySummarizerRegistry summarizers = new CommunitySummarizerRegistry(
            List.of(stubSummarizer("kwiki-summarizer-1", PluginAvailability.ok())));
    private final GraphBuildBatchService service = new GraphBuildBatchService(repository, null,
            pipelines, versions, SchemaTestFixtures.schemas(),
            new com.kwiki.graph.config.GraphPluginDefaults("kwiki-extractor-1",
                    "arcadedb-leiden-1", "kwiki-summarizer-1"),
            extractors, detectors, summarizers);
    private final EditableIndexConfig config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, "kwiki-schema-1");

    private GraphBuildBatchRequest request(String schema) {
        return request(schema, null, null, null);
    }

    private GraphBuildBatchRequest request(String schema, String extractor,
                                           String communityDetector, String summarizer) {
        return new GraphBuildBatchRequest("batch", "KNOWLEDGE_BASE", List.of(7L), 3, "kwiki-chunks-v3", schema, 1,
                "entity-linking-v1", false, "admin", null, extractor, communityDetector, summarizer);
    }

    private void target(boolean capable) {
        when(versions.findByVersionNumber(3)).thenReturn(Optional.of(SearchIndexVersion.bootstrapped(3, "kwiki-chunks-v3", config, "hash")));
        when(pipelines.supportsCapability(config, IndexCapability.ENTITY_LINKING)).thenReturn(capable);
    }

    private void allocatable() {
        when(repository.allocateCommunityIndexVersion(anyString(), eq(3), eq(1L))).thenReturn(5L);
        when(repository.createBatch(any())).thenReturn(11L);
        when(repository.allocateGraphVersion(7)).thenReturn(42L);
        when(repository.createRun(any())).thenReturn(21L);
    }

    @Test
    void 缺少能力时拒绝且不分配社区版本() {
        target(false);
        assertThatThrownBy(() -> service.submit(request("kwiki-schema-1"))).hasMessageContaining("图文 + 实体关联 v1（kwiki-schema-1）不支持实体关联");
        verify(repository, never()).allocateCommunityIndexVersion(anyString(), anyInt(), anyLong());
    }

    @Test
    void 能力满足时冻结字符串字段格式_社区整数结构独立() {
        target(true);
        allocatable();
        var result = service.submit(request("kwiki-schema-1"));
        var run = ArgumentCaptor.forClass(GraphBuildRunCommand.class);
        verify(repository).createRun(run.capture());
        assertThat(run.getValue().chunkIndexSchema()).isEqualTo("kwiki-schema-1");
        assertThat(run.getValue().communityIndexVersion()).isEqualTo(5);
        assertThat(result.runIds()).containsExactly(21L);
    }

    @Test
    void 身份变化时拒绝_空白字段格式不允许提交() {
        target(true);
        assertThatThrownBy(() -> service.submit(request("schema-other"))).hasMessageContaining("目标配置已变化");
        assertThatThrownBy(() -> request(" ")).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).createBatch(any());
    }

    @Test
    void 缺省时插件取默认值并写入批次与子任务() {
        target(true);
        allocatable();

        service.submit(request("kwiki-schema-1"));

        var batch = ArgumentCaptor.forClass(GraphBuildBatchCommand.class);
        verify(repository).createBatch(batch.capture());
        assertThat(batch.getValue().extractor()).isEqualTo("kwiki-extractor-1");
        assertThat(batch.getValue().communityDetector()).isEqualTo("arcadedb-leiden-1");
        assertThat(batch.getValue().summarizer()).isEqualTo("kwiki-summarizer-1");
        var run = ArgumentCaptor.forClass(GraphBuildRunCommand.class);
        verify(repository).createRun(run.capture());
        assertThat(run.getValue().extractor()).isEqualTo("kwiki-extractor-1");
        assertThat(run.getValue().summarizer()).isEqualTo("kwiki-summarizer-1");
    }

    @Test
    void 请求显式选择已注册且可用的插件() {
        target(true);
        allocatable();

        service.submit(request("kwiki-schema-1", "kwiki-extractor-1",
                "arcadedb-leiden-1", "kwiki-summarizer-1"));

        var batch = ArgumentCaptor.forClass(GraphBuildBatchCommand.class);
        verify(repository).createBatch(batch.capture());
        assertThat(batch.getValue().extractor()).isEqualTo("kwiki-extractor-1");
        assertThat(batch.getValue().communityDetector()).isEqualTo("arcadedb-leiden-1");
    }

    @Test
    void 未注册的插件拒绝提交() {
        target(true);

        assertThatThrownBy(() -> service.submit(request("kwiki-schema-1", "no-such-extractor",
                        null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("抽取器 no-such-extractor 不可用：未注册");
        verify(repository, never()).createBatch(any());
    }

    @Test
    void 不可用的插件拒绝提交并给出原因() {
        target(true);
        GraphBuildBatchService rejecting = new GraphBuildBatchService(repository, null,
                pipelines, versions, SchemaTestFixtures.schemas(),
                new com.kwiki.graph.config.GraphPluginDefaults("kwiki-extractor-1",
                        "arcadedb-leiden-1", "kwiki-summarizer-1"),
                new GraphExtractorRegistry(List.of(stubExtractor("kwiki-extractor-1",
                        PluginAvailability.ok()))),
                new CommunityDetectorRegistry(List.of(stubDetector("arcadedb-leiden-1",
                        PluginAvailability.unavailable("ArcadeDB 未配置")))),
                summarizers);

        assertThatThrownBy(() -> rejecting.submit(request("kwiki-schema-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("社区检测 arcadedb-leiden-1 不可用：ArcadeDB 未配置");
        verify(repository, never()).createBatch(any());
    }

    private static GraphExtractorPlugin stubExtractor(String id, PluginAvailability availability) {
        return new GraphExtractorPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String promptVersion() {
                return "p";
            }

            @Override
            public GraphExtractionResult extract(GraphExtractionInput input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PluginAvailability availability() {
                return availability;
            }
        };
    }

    private static CommunityDetectorPlugin stubDetector(String id, PluginAvailability availability) {
        return new CommunityDetectorPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public CommunityDetectionResult detect(CommunityDetectionInput input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PluginAvailability availability() {
                return availability;
            }
        };
    }

    private static CommunitySummarizerPlugin stubSummarizer(String id, PluginAvailability availability) {
        return new CommunitySummarizerPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String promptVersion() {
                return "p";
            }

            @Override
            public String modelName() {
                return "m";
            }

            @Override
            public CommunitySummary summarize(CommunitySummaryInput input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PluginAvailability availability() {
                return availability;
            }
        };
    }
}
