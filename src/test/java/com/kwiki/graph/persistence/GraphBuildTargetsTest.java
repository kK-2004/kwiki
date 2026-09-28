package com.kwiki.graph.persistence;

import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphBuildTargetsTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GraphBuildTargets targets = new GraphBuildTargets(versions, jdbc);

    private void version(int number, int schema) {
        EditableIndexConfig config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, schema);
        when(versions.findByVersionNumber(number)).thenReturn(Optional.of(SearchIndexVersion.bootstrapped(
                number, "kwiki-chunks-v" + number, config, new ChunkMappingBuilder().mappingHash(1024, schema))));
    }

    @Test
    void 全量范围在服务端展开为全部活跃知识库_其余参数取自所选版本() {
        version(3, 3);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(4L, 9L));

        GraphBuildBatchRequest request = targets.request("k1", "ALL", List.of(), 3, null, true, "admin", null);

        assertThat(request.knowledgeBaseIds()).containsExactly(4L, 9L);
        assertThat(request.chunkPhysicalIndex()).isEqualTo("kwiki-chunks-v3");
        assertThat(request.mappingSchemaVersion()).isEqualTo(3);
        assertThat(request.configRevision()).isEqualTo(1);
        assertThat(request.entityLinkingVersion()).isEqualTo(VersionedIndexingPipelineRegistry.ENTITY_LINKING_VERSION);
        assertThat(request.autoPublish()).isTrue();
    }

    @Test
    void 全量范围忽略调用方传入的知识库列表() {
        version(3, 3);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(4L));
        assertThat(targets.request("k1", "ALL", List.of(99L), 3, null, false, "admin", null).knowledgeBaseIds())
                .containsExactly(4L);
    }

    @Test
    void 指定知识库范围必须恰好一个() {
        version(3, 3);
        assertThat(targets.request("k1", "KNOWLEDGE_BASE", List.of(7L), 3, null, false, "admin", null)
                .knowledgeBaseIds()).containsExactly(7L);
        assertThatThrownBy(() -> targets.request("k1", "KNOWLEDGE_BASE", List.of(), 3, null, false, "admin", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("只能选择一个");
    }

    @Test
    void 实体链接版本与流水线不一致时拒绝_一致或为空时通过() {
        version(3, 3);
        assertThatThrownBy(() -> targets.request("k1", "KNOWLEDGE_BASE", List.of(7L), 3, "v1", false, "admin", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entity-linking-v1");
        targets.request("k1", "KNOWLEDGE_BASE", List.of(7L), 3, "entity-linking-v1", false, "admin", null);
        targets.request("k1", "KNOWLEDGE_BASE", List.of(7L), 3, " ", false, "admin", null);
    }

    @Test
    void 版本不存在或结构版本不足时拒绝() {
        when(versions.findByVersionNumber(8)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> targets.request("k1", "ALL", List.of(), 8, null, false, "admin", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v8");
        version(2, 1);
        assertThatThrownBy(() -> targets.request("k1", "ALL", List.of(), 2, null, false, "admin", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v3 及以上");
    }

    @Test
    void 没有活跃知识库时拒绝全量构建() {
        version(3, 3);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of());
        assertThatThrownBy(() -> targets.request("k1", "ALL", List.of(), 3, null, false, "admin", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有活跃的知识库");
    }
}
