package com.kwiki.infrastructure.ai.registry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 基于 JdbcTemplate 的模型目录存储（V40）。 */
@Repository
public class JdbcModelConfigStore implements ModelConfigStore {

    private final JdbcTemplate jdbc;

    public JdbcModelConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ModelCatalog load() {
        long version = currentVersion();
        List<ModelProvider> providers = jdbc.query("""
                SELECT id, name, protocol, base_url, api_key_cipher, api_key_hint, request_timeout_ms,
                       max_retries, max_concurrency, qps, enabled
                FROM model_provider ORDER BY id
                """, (rs, i) -> new ModelProvider(rs.getLong("id"), rs.getString("name"),
                rs.getString("protocol"), rs.getString("base_url"), rs.getString("api_key_cipher"),
                rs.getString("api_key_hint"), rs.getInt("request_timeout_ms"), rs.getInt("max_retries"),
                nullableInt(rs, "max_concurrency"), nullableDouble(rs, "qps"), rs.getBoolean("enabled")));
        Map<Long, Set<ModelTag>> tags = new HashMap<>();
        jdbc.query("SELECT model_id, tag FROM model_definition_tag", (ResultSet rs) -> {
            tags.computeIfAbsent(rs.getLong("model_id"), k -> EnumSet.noneOf(ModelTag.class))
                    .add(ModelTag.fromDb(rs.getString("tag")));
        });
        List<ModelDefinition> models = jdbc.query("""
                SELECT id, provider_id, model_name, display_name, dimensions, max_output_tokens,
                       supports_tools, supports_thinking, enabled
                FROM model_definition ORDER BY provider_id, id
                """, (rs, i) -> new ModelDefinition(rs.getLong("id"), rs.getLong("provider_id"),
                rs.getString("model_name"), rs.getString("display_name"),
                tags.getOrDefault(rs.getLong("id"), Set.of()), nullableInt(rs, "dimensions"),
                nullableInt(rs, "max_output_tokens"), rs.getBoolean("supports_tools"),
                rs.getBoolean("supports_thinking"), rs.getBoolean("enabled")));
        Map<ModelPurpose, ModelAssignment> assignments = new EnumMap<>(ModelPurpose.class);
        jdbc.query("SELECT purpose, model_id, temperature, max_tokens, thinking FROM model_assignment",
                (ResultSet rs) -> {
                    ModelPurpose purpose;
                    try {
                        purpose = ModelPurpose.valueOf(rs.getString("purpose"));
                    } catch (IllegalArgumentException unknown) {
                        return; // 新版本删除的用途：旧行忽略
                    }
                    Object thinking = rs.getObject("thinking");
                    assignments.put(purpose, new ModelAssignment(purpose, nullableLong(rs, "model_id"),
                            nullableDouble(rs, "temperature"), nullableInt(rs, "max_tokens"),
                            thinking == null ? null : rs.getBoolean("thinking")));
                });
        return new ModelCatalog(version, providers, models, assignments);
    }

    @Override
    public long currentVersion() {
        Long version = jdbc.queryForObject("SELECT version FROM model_config_version WHERE id = 1", Long.class);
        return version == null ? 0 : version;
    }

    @Override
    public long bumpVersion() {
        jdbc.update("UPDATE model_config_version SET version = version + 1 WHERE id = 1");
        return currentVersion();
    }

    @Override
    public boolean isEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM model_provider", Integer.class);
        return count == null || count == 0;
    }

    @Override
    public long insertProvider(ModelProvider p) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO model_provider (name, protocol, base_url, api_key_cipher, api_key_hint,
                        request_timeout_ms, max_retries, max_concurrency, qps, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindProvider(s, p);
            return s;
        }, keys);
        return requireKey(keys);
    }

    @Override
    public void updateProvider(ModelProvider p) {
        jdbc.update(connection -> {
            PreparedStatement s = connection.prepareStatement("""
                    UPDATE model_provider SET name = ?, protocol = ?, base_url = ?, api_key_cipher = ?,
                        api_key_hint = ?, request_timeout_ms = ?, max_retries = ?, max_concurrency = ?,
                        qps = ?, enabled = ?
                    WHERE id = ?
                    """);
            bindProvider(s, p);
            s.setLong(11, p.id());
            return s;
        });
    }

    @Override
    public void deleteProvider(long id) {
        jdbc.update("DELETE FROM model_provider WHERE id = ?", id);
    }

    @Override
    public long insertModel(ModelDefinition m) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO model_definition (provider_id, model_name, display_name, dimensions,
                        max_output_tokens, supports_tools, supports_thinking, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindModel(s, m);
            return s;
        }, keys);
        long id = requireKey(keys);
        replaceTags(id, m.tags());
        return id;
    }

    @Override
    public void updateModel(ModelDefinition m) {
        jdbc.update(connection -> {
            PreparedStatement s = connection.prepareStatement("""
                    UPDATE model_definition SET provider_id = ?, model_name = ?, display_name = ?,
                        dimensions = ?, max_output_tokens = ?, supports_tools = ?, supports_thinking = ?,
                        enabled = ?
                    WHERE id = ?
                    """);
            bindModel(s, m);
            s.setLong(9, m.id());
            return s;
        });
        replaceTags(m.id(), m.tags());
    }

    @Override
    public void deleteModel(long id) {
        jdbc.update("DELETE FROM model_definition_tag WHERE model_id = ?", id);
        jdbc.update("DELETE FROM model_definition WHERE id = ?", id);
    }

    @Override
    public void saveAssignments(Collection<ModelAssignment> assignments) {
        for (ModelAssignment a : assignments) {
            jdbc.update("""
                    INSERT INTO model_assignment (purpose, model_id, temperature, max_tokens, thinking)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE model_id = VALUES(model_id), temperature = VALUES(temperature),
                        max_tokens = VALUES(max_tokens), thinking = VALUES(thinking)
                    """, a.purpose().name(), a.modelId(), a.temperature(), a.maxTokens(), a.thinking());
        }
    }

    @Override
    public int renameEmbeddingProviderReferences(String from, String to) {
        return jdbc.update("UPDATE search_index_version SET embedding_provider = ? WHERE embedding_provider = ?",
                to, from);
    }

    @Override
    public List<Integer> indexVersionsReferencing(String providerName, String modelName) {
        return jdbc.queryForList("""
                SELECT version_number FROM search_index_version
                WHERE deleted_at IS NULL AND embedding_provider = ? AND embedding_model = ?
                ORDER BY version_number
                """, Integer.class, providerName, modelName);
    }

    @Override
    public void lockVersionRow() {
        jdbc.queryForObject("SELECT version FROM model_config_version WHERE id = 1 FOR UPDATE", Long.class);
    }

    private void replaceTags(long modelId, Set<ModelTag> tags) {
        jdbc.update("DELETE FROM model_definition_tag WHERE model_id = ?", modelId);
        List<Object[]> rows = new ArrayList<>();
        for (ModelTag tag : tags) {
            rows.add(new Object[]{modelId, tag.dbValue()});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO model_definition_tag (model_id, tag) VALUES (?, ?)", rows);
        }
    }

    private static void bindProvider(PreparedStatement s, ModelProvider p) throws SQLException {
        s.setString(1, p.name());
        s.setString(2, p.protocol() == null ? ModelProvider.OPENAI_COMPATIBLE : p.protocol());
        s.setString(3, p.baseUrl());
        s.setString(4, p.apiKeyCipher());
        s.setString(5, p.apiKeyHint());
        s.setInt(6, p.requestTimeoutMs());
        s.setInt(7, p.maxRetries());
        setNullable(s, 8, p.maxConcurrency(), Types.INTEGER);
        setNullable(s, 9, p.qps(), Types.DOUBLE);
        s.setBoolean(10, p.enabled());
    }

    private static void bindModel(PreparedStatement s, ModelDefinition m) throws SQLException {
        s.setLong(1, m.providerId());
        s.setString(2, m.modelName());
        s.setString(3, m.displayName());
        setNullable(s, 4, m.dimensions(), Types.INTEGER);
        setNullable(s, 5, m.maxOutputTokens(), Types.INTEGER);
        s.setBoolean(6, m.supportsTools());
        s.setBoolean(7, m.supportsThinking());
        s.setBoolean(8, m.enabled());
    }

    private static void setNullable(PreparedStatement s, int index, Object value, int type) throws SQLException {
        if (value == null) {
            s.setNull(index, type);
        } else {
            s.setObject(index, value, type);
        }
    }

    private static long requireKey(GeneratedKeyHolder keys) {
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("model catalog insert returned no key");
        }
        return key.longValue();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
