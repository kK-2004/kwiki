package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphAdminQueryServiceTest {

    @Test
    void 列名转为管理端使用的驼峰字段名() {
        assertThat(GraphAdminQueryService.camelKey("chunk_index_version")).isEqualTo("chunkIndexVersion");
        assertThat(GraphAdminQueryService.camelKey("ID")).isEqualTo("id");
        assertThat(GraphAdminQueryService.camelKey("snapshot_state")).isEqualTo("snapshotState");
    }

    @Test
    void 批次列表返回驼峰字段_前端不再取到undefined() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("scope_kind", "ALL");
        row.put("chunk_index_version", 5);
        row.put("community_index_version", 2);
        row.put("requested_by", "kk");
        when(jdbc.queryForList(contains("FROM graph_build_batch"))).thenReturn(List.of(row));

        Map<String, Object> batch = new GraphAdminQueryService(jdbc).batches().get(0);

        assertThat(batch).containsEntry("scopeKind", "ALL").containsEntry("chunkIndexVersion", 5)
                .containsEntry("communityIndexVersion", 2).containsEntry("requestedBy", "kk")
                .doesNotContainKey("chunk_index_version");
    }

    @Test
    void community版本及其物理索引都转为驼峰() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("version_number", 3);
        version.put("mapping_schema_version", 3);
        when(jdbc.queryForList(contains("FROM community_index_version"))).thenReturn(List.of(version));
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("kb_id", 7L);
        index.put("community_physical_index", "kwiki-communities-v3-kb7");
        when(jdbc.queryForList(contains("FROM graph_build_run"), eq(3))).thenReturn(List.of(index));

        Map<String, Object> view = new GraphAdminQueryService(jdbc).communityVersions().get(0);

        assertThat(view).containsEntry("versionNumber", 3).containsEntry("mappingSchemaVersion", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> physical = (List<Map<String, Object>>) view.get("physicalIndexes");
        assertThat(physical.get(0)).containsEntry("kbId", 7L)
                .containsEntry("communityPhysicalIndex", "kwiki-communities-v3-kb7");
    }

    @Test
    void 时间列按UTC转为Instant_由展示时区统一格式化() {
        java.time.LocalDateTime utcWall = java.time.LocalDateTime.of(2026, 10, 2, 15, 37);
        assertThat(GraphAdminQueryService.timestamp(utcWall))
                .isEqualTo(java.time.Instant.parse("2026-10-02T15:37:00Z"));
        java.time.LocalDate date = java.time.LocalDate.of(2026, 10, 2);
        assertThat(GraphAdminQueryService.timestamp(date)).isSameAs(date);
    }
}
