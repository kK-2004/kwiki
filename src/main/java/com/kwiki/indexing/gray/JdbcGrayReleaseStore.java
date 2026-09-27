package com.kwiki.indexing.gray;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 基于 JdbcTemplate 的灰度发布存储。 */
@Repository
public class JdbcGrayReleaseStore implements GrayReleaseStore {

    private static final String SELECT_RELEASE = """
            SELECT id, name, parser_version, index_version_number, status, last_error,
                   created_by, created_at, switched_at, ended_at
            FROM index_gray_release
            """;

    private final JdbcTemplate jdbc;

    public JdbcGrayReleaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(String name, String parserVersion, int indexVersionNumber, String createdBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO index_gray_release (name, parser_version, index_version_number, status, created_by)
                    VALUES (?, ?, ?, 'CREATED', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, parserVersion);
            statement.setInt(3, indexVersionNumber);
            statement.setString(4, createdBy);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("gray release insert returned no key");
        }
        return key.longValue();
    }

    @Override
    public void insertKbs(long releaseId, Collection<Long> kbIds) {
        List<Object[]> rows = kbIds.stream().distinct()
                .map(kbId -> new Object[] {releaseId, kbId, kbId}).toList();
        jdbc.batchUpdate("INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (?, ?, ?)", rows);
    }

    @Override
    public Optional<GrayRelease> find(long id) {
        return query(SELECT_RELEASE + " WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public List<GrayRelease> findAll() {
        return query(SELECT_RELEASE + " ORDER BY id DESC");
    }

    @Override
    public List<GrayRelease> findByStatus(GrayReleaseStatus status) {
        return query(SELECT_RELEASE + " WHERE status = ? ORDER BY id", status.name());
    }

    @Override
    public Map<Long, Long> activeReleaseByKb(Collection<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = kbIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<Long, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT active_kb_id, release_id FROM index_gray_release_kb WHERE active_kb_id IN ("
                        + placeholders + ")",
                (ResultSet rs) -> { result.put(rs.getLong(1), rs.getLong(2)); }, kbIds.toArray());
        return result;
    }

    @Override
    public boolean transition(long id, GrayReleaseStatus expected, GrayReleaseStatus next, String lastError) {
        return jdbc.update("UPDATE index_gray_release SET status = ?, last_error = ? WHERE id = ? AND status = ?",
                next.name(), lastError, id, expected.name()) == 1;
    }

    @Override
    public void rename(long id, String name) {
        jdbc.update("UPDATE index_gray_release SET name = ? WHERE id = ?", name, id);
    }

    @Override
    public boolean markSwitched(long id) {
        return jdbc.update("UPDATE index_gray_release SET status = 'SWITCHED', last_error = NULL,"
                + " switched_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND status = 'SYNCED'", id) == 1;
    }

    @Override
    public boolean end(long id, GrayReleaseStatus expected) {
        int updated = jdbc.update("UPDATE index_gray_release SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP(6)"
                + " WHERE id = ? AND status = ?", id, expected.name());
        if (updated != 1) {
            return false;
        }
        jdbc.update("UPDATE index_gray_release_kb SET active_kb_id = NULL WHERE release_id = ?", id);
        return true;
    }

    @Override
    public List<SwitchedRoute> switchedRoutes() {
        return jdbc.query("""
                SELECT v.physical_name, r.parser_version, k.kb_id
                FROM index_gray_release r
                JOIN search_index_version v ON v.version_number = r.index_version_number
                JOIN index_gray_release_kb k ON k.release_id = r.id
                WHERE r.status = 'SWITCHED' AND k.active_kb_id IS NOT NULL AND v.deleted_at IS NULL
                """, (rs, row) -> new SwitchedRoute(rs.getString(1), rs.getString(2), rs.getLong(3)));
    }

    private List<GrayRelease> query(String sql, Object... args) {
        List<GrayRelease> releases = jdbc.query(sql, (rs, row) -> release(rs, List.of()), args);
        if (releases.isEmpty()) {
            return releases;
        }
        Map<Long, List<GrayRelease.Kb>> kbs = kbs(releases.stream().map(GrayRelease::id).toList());
        List<GrayRelease> result = new ArrayList<>(releases.size());
        for (GrayRelease release : releases) {
            result.add(new GrayRelease(release.id(), release.name(), release.parserVersion(),
                    release.indexVersionNumber(), release.status(), release.lastError(), release.createdBy(),
                    release.createdAt(), release.switchedAt(), release.endedAt(),
                    kbs.getOrDefault(release.id(), List.of())));
        }
        return result;
    }

    private Map<Long, List<GrayRelease.Kb>> kbs(List<Long> releaseIds) {
        String placeholders = releaseIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<Long, List<GrayRelease.Kb>> result = new LinkedHashMap<>();
        jdbc.query("SELECT g.release_id, g.kb_id, COALESCE(b.name, CONCAT('知识库 #', g.kb_id))"
                        + " FROM index_gray_release_kb g LEFT JOIN knowledge_base b ON b.id = g.kb_id"
                        + " WHERE g.release_id IN (" + placeholders + ") ORDER BY g.kb_id",
                (ResultSet rs) -> {
                    result.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>())
                            .add(new GrayRelease.Kb(rs.getLong(2), rs.getString(3)));
                }, releaseIds.toArray());
        return result;
    }

    private static GrayRelease release(ResultSet rs, List<GrayRelease.Kb> kbs) throws SQLException {
        return new GrayRelease(rs.getLong("id"), rs.getString("name"), rs.getString("parser_version"),
                rs.getInt("index_version_number"), GrayReleaseStatus.valueOf(rs.getString("status")),
                rs.getString("last_error"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("switched_at")), instant(rs.getTimestamp("ended_at")), kbs);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
