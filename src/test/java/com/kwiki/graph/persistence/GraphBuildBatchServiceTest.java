package com.kwiki.graph.persistence;

import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.plugin.IndexCapability;
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
    private final GraphBuildBatchService service = new GraphBuildBatchService(repository, null,
            pipelines, versions, SchemaTestFixtures.schemas(),
            new com.kwiki.graph.config.GraphPluginDefaults("kwiki-extractor-1",
                    "arcadedb-leiden-1", "kwiki-summarizer-1"));
    private final EditableIndexConfig config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, "kwiki-schema-1");
    private GraphBuildBatchRequest request(String schema) {
        return new GraphBuildBatchRequest("batch", "KNOWLEDGE_BASE", List.of(7L), 3, "kwiki-chunks-v3", schema, 1,
                "entity-linking-v1", false, "admin", null);
    }
    private void target(boolean capable) {
        when(versions.findByVersionNumber(3)).thenReturn(Optional.of(SearchIndexVersion.bootstrapped(3, "kwiki-chunks-v3", config, "hash")));
        when(pipelines.supportsCapability(config, IndexCapability.ENTITY_LINKING)).thenReturn(capable);
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
        when(repository.allocateCommunityIndexVersion(anyString(), eq(3), eq(1L))).thenReturn(5L);
        when(repository.createBatch(any())).thenReturn(11L);
        when(repository.allocateGraphVersion(7)).thenReturn(42L);
        when(repository.createRun(any())).thenReturn(21L);
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
}
