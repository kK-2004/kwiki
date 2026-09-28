package com.kwiki.wiki.api;

import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.persistence.GraphAdminCommandService;
import com.kwiki.graph.persistence.GraphAdminQueryService;
import com.kwiki.graph.persistence.GraphBuildBatchRequest;
import com.kwiki.graph.persistence.GraphBuildBatchService;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildSubmission;
import com.kwiki.graph.persistence.GraphBuildTargets;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphBuildAdminControllerSubmitTest {

    private final GraphBuildBatchService batches = mock(GraphBuildBatchService.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser admin = mock(CurrentUser.class);
    private final GraphBuildAdminController controller = new GraphBuildAdminController(batches,
            mock(GraphAdminQueryService.class), mock(GraphAdminCommandService.class), mock(GraphBuildRepository.class),
            new GraphProperties(true, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "0 0 2 * * *", "Asia/Shanghai",
                    false, Duration.ofMinutes(10), 2, Duration.ofMinutes(30), GraphProperties.Capacity.defaults()),
            new GraphBuildTargets(versions, jdbc));

    private void version(int number, int schema) {
        EditableIndexConfig config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "qwen", 1024, schema);
        when(versions.findByVersionNumber(number)).thenReturn(Optional.of(SearchIndexVersion.bootstrapped(
                number, "kwiki-chunks-v" + number, config, new ChunkMappingBuilder().mappingHash(1024, schema))));
    }

    @Test
    void 全量提交由服务端展开知识库并推导其余参数_忽略客户端旧字段() {
        when(admin.username()).thenReturn("admin");
        version(3, 3);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(4L, 9L));
        when(batches.submit(any())).thenReturn(new GraphBuildSubmission(11L, 2L, List.of(21L, 22L), false));

        // 旧客户端仍会带上 mappingSchemaVersion=1、entity "entity-linking-v1" 等字段：结构版本与物理名以服务端为准
        controller.submit(admin, "k1", new GraphBuildAdminController.SubmitRequest(
                "ALL", List.of(), 3, "bogus-index", 1, 99L, "entity-linking-v1", null));

        ArgumentCaptor<GraphBuildBatchRequest> captor = ArgumentCaptor.forClass(GraphBuildBatchRequest.class);
        verify(batches).submit(captor.capture());
        GraphBuildBatchRequest request = captor.getValue();
        assertThat(request.knowledgeBaseIds()).containsExactly(4L, 9L);
        assertThat(request.chunkPhysicalIndex()).isEqualTo("kwiki-chunks-v3");
        assertThat(request.mappingSchemaVersion()).isEqualTo(3);
        assertThat(request.configRevision()).isEqualTo(1);
        assertThat(request.requestedBy()).isEqualTo("admin");
    }

    @Test
    void 参数不合法时返回中文冲突且不提交() {
        version(3, 3);
        assertThatThrownBy(() -> controller.submit(admin, "k2", new GraphBuildAdminController.SubmitRequest(
                "KNOWLEDGE_BASE", List.of(7L), 3, null, null, null, "v1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("entity-linking-v1");
        verify(batches, never()).submit(any());
    }
}
