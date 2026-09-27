package com.kwiki.indexing.gray;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexVersionKbScopeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IndexVersionKbScope scope = new IndexVersionKbScope(jdbc);

    @Test
    void 无范围行的版本视为全局版本_接受任何知识库() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(3))).thenReturn(List.of());
        assertThat(scope.isScoped(3)).isFalse();
        assertThat(scope.accepts(3, 42L)).isTrue();
    }

    @Test
    void 有范围的版本只接受范围内知识库() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(4))).thenReturn(List.of(7L, 9L));
        assertThat(scope.kbIds(4)).isEqualTo(Set.of(7L, 9L));
        assertThat(scope.isScoped(4)).isTrue();
        assertThat(scope.accepts(4, 7L)).isTrue();
        assertThat(scope.accepts(4, 8L)).isFalse();
    }

    @Test
    void 登记范围逐个插入知识库() {
        scope.register(5, List.of(1L, 2L));
        verify(jdbc).batchUpdate(eq("INSERT INTO search_index_version_kb_scope (version_number, kb_id) VALUES (?, ?)"),
                any(List.class));
    }

    @Test
    void SQL过滤片段对全局版本不生效_对范围版本限定知识库() {
        String fragment = IndexVersionKbScope.sqlFilter("p.kb_id");
        assertThat(fragment).startsWith(" AND (")
                .contains("NOT EXISTS (SELECT 1 FROM search_index_version_kb_scope s0 WHERE s0.version_number = ?)")
                .contains("p.kb_id IN (SELECT s1.kb_id FROM search_index_version_kb_scope s1 WHERE s1.version_number = ?)");
    }
}
