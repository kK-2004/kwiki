package com.kwiki.graph.persistence;

import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentChunkScheduledBatchFactoryTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentChunkScheduledBatchFactory factory =
            new CurrentChunkScheduledBatchFactory(new GraphBuildTargets(versions, jdbc), false);
    private final LocalDate today = LocalDate.of(2026, 9, 28);

    private void selected(int schema) {
        EditableIndexConfig config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, schema);
        SearchIndexVersion version = SearchIndexVersion.bootstrapped(3, "kwiki-chunks-v3", config,
                new ChunkMappingBuilder().mappingHash(1024, schema));
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(version));
    }

    @Test
    void 以当前全局版本与全部活跃知识库生成当天的全量批次() {
        selected(3);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(9L, 4L));

        GraphBuildBatchRequest request = factory.create(today).orElseThrow();

        assertThat(request.scopeKind()).isEqualTo("ALL");
        assertThat(request.knowledgeBaseIds()).containsExactly(4L, 9L);
        assertThat(request.chunkIndexVersion()).isEqualTo(3);
        assertThat(request.chunkPhysicalIndex()).isEqualTo("kwiki-chunks-v3");
        assertThat(request.mappingSchemaVersion()).isEqualTo(3);
        assertThat(request.configRevision()).isEqualTo(1);
        assertThat(request.entityLinkingVersion()).isEqualTo(VersionedIndexingPipelineRegistry.ENTITY_LINKING_VERSION);
        assertThat(request.scheduleDate()).isEqualTo(today);
        // 按日期幂等：同一天重复调用得到同一幂等键，批次服务据此回放而非重复创建
        assertThat(request.idempotencyKey()).isEqualTo("GRAPH_SCHEDULE:2026-09-28");
        assertThat(request.requestedBy()).isEqualTo("graph-scheduler");
    }

    @Test
    void 没有已发布的全局版本时跳过() {
        when(versions.findBySelectedTrue()).thenReturn(Optional.empty());
        assertThat(factory.create(today)).isEmpty();
    }

    @Test
    void 全局版本不支持实体映射时跳过() {
        selected(2);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(4L));
        assertThat(factory.create(today)).isEmpty();
    }

    @Test
    void 没有活跃知识库时跳过() {
        selected(3);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of());
        assertThat(factory.create(today)).isEmpty();
    }
}
