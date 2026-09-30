package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixedRangeRebuildScannerMigrationTest {

    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final SearchIndexRebuildRangeRepository ranges = mock(SearchIndexRebuildRangeRepository.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RebuildTargetEnqueuer enqueuer = mock(RebuildTargetEnqueuer.class);
    private final IndexingProperties properties = IndexVersionWriteServiceTest.properties(true);
    private final FixedRangeRebuildScanner scanner = new FixedRangeRebuildScanner(runs, ranges,
            versions, jdbc, enqueuer, mock(PlatformTransactionManager.class), properties);

    @SuppressWarnings("unchecked")
    private String capturePageSql(Long cutoffEventId, List<Object> argsOut) {
        SearchIndexRebuildRange range = new SearchIndexRebuildRange(7L, "PAGE", 1, 100);
        when(ranges.findByIdForUpdate(anyLong())).thenReturn(Optional.of(range));
        when(runs.findById(7L)).thenReturn(Optional.of(mock(SearchIndexRebuildRun.class)));
        // 用 thenAnswer 记录原始参数，避免 Mockito 5 对可变参数捕获的歧义
        List<Object[]> calls = new java.util.ArrayList<>();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            calls.add(invocation.getRawArguments());
            return List.of();
        });

        scanner.processBatch(7L, 1L, 2, "kwiki-chunks-v2", false, cutoffEventId);

        Object[] raw = calls.get(0);
        argsOut.addAll(java.util.Arrays.asList((Object[]) raw[2]));
        return (String) raw[0];
    }

    @Test
    void 迁移模式_跳过双写起点之后有变更事件的资源() {
        List<Object> args = new java.util.ArrayList<>();
        String sql = capturePageSql(42L, args);
        assertThat(sql).contains("NOT EXISTS").contains("search_index_change_event").contains("e.id>?");
        assertThat(args).contains(42L);
    }

    @Test
    void 非迁移模式_不加截止过滤() {
        List<Object> args = new java.util.ArrayList<>();
        String sql = capturePageSql(null, args);
        assertThat(sql).doesNotContain("search_index_change_event");
    }
}
