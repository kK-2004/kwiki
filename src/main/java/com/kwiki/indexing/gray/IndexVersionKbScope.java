package com.kwiki.indexing.gray;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 索引版本的知识库范围。没有任何范围行的版本是全局版本；
 * 灰度版本登记其知识库集合，重建、补齐、校验与写入都只处理范围内的知识库。
 */
@Component
public class IndexVersionKbScope {

    private static final String SELECT_KB_IDS =
            "SELECT kb_id FROM search_index_version_kb_scope WHERE version_number = ?";
    private static final String INSERT =
            "INSERT INTO search_index_version_kb_scope (version_number, kb_id) VALUES (?, ?)";

    private final JdbcTemplate jdbc;

    @Autowired
    public IndexVersionKbScope(ObjectProvider<JdbcTemplate> jdbc) {
        this(jdbc.getIfAvailable());
    }

    IndexVersionKbScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 资源查询的范围过滤片段：全局版本不生效，范围版本限定知识库列。
     * 调用方必须按顺序追加两个参数：versionNumber, versionNumber。
     */
    public static String sqlFilter(String kbColumn) {
        return " AND (NOT EXISTS (SELECT 1 FROM search_index_version_kb_scope s0 WHERE s0.version_number = ?)"
                + " OR " + kbColumn
                + " IN (SELECT s1.kb_id FROM search_index_version_kb_scope s1 WHERE s1.version_number = ?))";
    }

    public void register(int versionNumber, Collection<Long> kbIds) {
        List<Object[]> rows = kbIds.stream().distinct()
                .map(kbId -> new Object[] {versionNumber, kbId}).toList();
        requireJdbc().batchUpdate(INSERT, rows);
    }

    public Set<Long> kbIds(int versionNumber) {
        if (jdbc == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.queryForList(SELECT_KB_IDS, Long.class, versionNumber));
    }

    public boolean isScoped(int versionNumber) {
        return !kbIds(versionNumber).isEmpty();
    }

    public boolean accepts(int versionNumber, long kbId) {
        Set<Long> scope = kbIds(versionNumber);
        return scope.isEmpty() || scope.contains(kbId);
    }

    private JdbcTemplate requireJdbc() {
        if (jdbc == null) {
            throw new IllegalStateException("search index version scope registry is unavailable");
        }
        return jdbc;
    }
}
