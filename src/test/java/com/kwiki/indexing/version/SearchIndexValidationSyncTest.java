package com.kwiki.indexing.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchIndexValidationSyncTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SearchIndexValidationService service = new SearchIndexValidationService(
            mock(SearchIndexVersionRepository.class), mock(SearchIndexRebuildRunRepository.class),
            mock(SearchIndexRebuildRangeRepository.class),
            mock(SearchIndexValidationReportRepository.class),
            mock(ElasticsearchIndexManager.class), mock(ChunkMappingBuilder.class),
            new ObjectMapper(), jdbc);
    private final SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
    private final SearchIndexVersion version = mock(SearchIndexVersion.class);

    @BeforeEach
    void setUp() {
        when(run.getId()).thenReturn(9L);
        when(run.getVersionNumber()).thenReturn(2);
        when(run.kind()).thenReturn(RebuildRunKind.MIGRATION);
        when(run.state()).thenReturn(RebuildRunState.COMPLETED);
        when(run.getBuildStartEventId()).thenReturn(42L);
        when(version.isWriteEnabled()).thenReturn(true);
        when(version.getWriteEnabledEventId()).thenReturn(42L);
        when(version.getCatchupStatus()).thenReturn(IndexCatchupStatus.CURRENT.name());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any())).thenReturn(0L);
    }

    @Test
    void 本会话迁移完成且双写积压清零_视为已同步() {
        assertThat(service.synchronizedState(run, version, 100L)).isTrue();
    }

    @Test
    void 旧的重建run不算同步() {
        when(run.kind()).thenReturn(RebuildRunKind.MANUAL);
        assertThat(service.synchronizedState(run, version, 100L)).isFalse();
    }

    @Test
    void 写入会话已重开_旧迁移不算同步() {
        when(version.getWriteEnabledEventId()).thenReturn(77L);
        assertThat(service.synchronizedState(run, version, 100L)).isFalse();
    }

    @Test
    void 双写仍有积压_不算同步() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any())).thenReturn(5L);
        assertThat(service.synchronizedState(run, version, 100L)).isFalse();
    }

    @Test
    void 积压只统计本写入会话起点之后的双写目标() {
        service.synchronizedState(run, version, 100L);
        verify(jdbc).queryForObject(contains("t.event_id>? AND t.event_id<=?"), eq(Long.class),
                eq(2), eq(42L), eq(100L), eq("REBUILD:9:%"));
    }
}
