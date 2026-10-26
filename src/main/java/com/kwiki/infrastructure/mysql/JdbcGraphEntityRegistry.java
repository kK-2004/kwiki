package com.kwiki.infrastructure.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityRegistryPort;
import com.kwiki.graph.GraphEntityType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 以 MySQL 唯一约束承载知识库内实体注册：同一知识库、同一消解版本下「归一化名称 + 类型」唯一，
 * 别名与争议判定结果分表保存。实体 ID 由知识库、类型、归一化名称与版本确定性生成。
 */
@Repository
// JdbcTemplate 恒由自动配置提供；组件扫描阶段 @ConditionalOnBean 看不到它，改按图功能开关注册
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class JdbcGraphEntityRegistry implements GraphEntityRegistryPort {

    /** 新规则下不再用上下文区分实体；旧唯一键要求非空，统一填 64 个 0。 */
    static final String NO_CONTEXT_HASH = "0".repeat(64);
    /** graph_entity_alias.normalized_alias 的列宽，更长的别名不索引。 */
    static final int MAX_ALIAS_LENGTH = 255;

    private static final String COLUMNS = "entity_id, canonical_name, entity_type, aliases_json, context_snippet";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGraphEntityRegistry(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<RegisteredEntity> findByName(long kbId, String normalizedName, GraphEntityType type,
                                                 String resolverVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM graph_entity_registry "
                        + "WHERE kb_id = ? AND normalized_name = ? AND entity_type = ? AND resolver_version = ?",
                rowMapper(), kbId, normalizedName, type.name(), resolverVersion).stream().findFirst();
    }

    @Override
    public List<RegisteredEntity> findByTerms(long kbId, Collection<String> normalizedTerms,
                                              String resolverVersion, int limit) {
        List<String> terms = normalizedTerms == null ? List.of() : normalizedTerms.stream()
                .filter(term -> term != null && !term.isEmpty()).distinct().toList();
        if (terms.isEmpty() || limit <= 0) {
            return List.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(terms.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(kbId);
        args.add(resolverVersion);
        args.addAll(terms);
        args.add(kbId);
        args.add(resolverVersion);
        args.addAll(terms);
        args.add(limit);
        // 名称命中（不限类型）∪ 别名命中；同一实体只返回一次
        return jdbc.query("SELECT " + COLUMNS + " FROM graph_entity_registry WHERE id IN ("
                        + "SELECT r.id FROM graph_entity_registry r WHERE r.kb_id = ? AND r.resolver_version = ? "
                        + "AND r.normalized_name IN (" + in + ") "
                        + "UNION SELECT r.id FROM graph_entity_registry r JOIN graph_entity_alias a "
                        + "ON a.kb_id = r.kb_id AND a.entity_id = r.entity_id AND a.resolver_version = r.resolver_version "
                        + "WHERE a.kb_id = ? AND a.resolver_version = ? AND a.normalized_alias IN (" + in + ")"
                        + ") ORDER BY id LIMIT ?",
                rowMapper(), args.toArray());
    }

    @Override
    public RegisteredEntity register(long kbId, GraphEntity candidate, String normalizedName,
                                     String contextSnippet, String resolverVersion) {
        String entityId = stableEntityId(kbId, candidate.entityType(), normalizedName, resolverVersion);
        jdbc.update("INSERT IGNORE INTO graph_entity_registry "
                        + "(kb_id, entity_id, canonical_name, entity_type, aliases_json, context_hash, "
                        + "resolver_version, normalized_name, context_snippet) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                kbId, entityId, bounded(candidate.canonicalName(), 500), candidate.entityType().name(),
                aliasesJson(candidate.aliases()), NO_CONTEXT_HASH, resolverVersion,
                normalizedName, bounded(contextSnippet, 500));
        // 并发下同名同类型可能已被其他块登记：以唯一键回读，返回实际生效的那一行
        return findByName(kbId, normalizedName, candidate.entityType(), resolverVersion)
                .orElseGet(() -> new RegisteredEntity(entityId, candidate.canonicalName(),
                        candidate.entityType(), candidate.aliases(), contextSnippet));
    }

    @Override
    public void addAliases(long kbId, String entityId, Collection<String> normalizedAliases, String resolverVersion) {
        if (normalizedAliases == null) {
            return;
        }
        for (String alias : normalizedAliases) {
            if (alias == null || alias.isEmpty() || alias.length() > MAX_ALIAS_LENGTH) {
                continue;
            }
            jdbc.update("INSERT IGNORE INTO graph_entity_alias "
                            + "(kb_id, resolver_version, entity_id, normalized_alias) VALUES (?, ?, ?, ?)",
                    kbId, resolverVersion, entityId, alias);
        }
    }

    @Override
    public Optional<Boolean> findMergeDecision(long kbId, String resolverVersion, String candidateKey,
                                               String entityId) {
        return jdbc.query("SELECT same_entity FROM graph_entity_merge_decision "
                        + "WHERE kb_id = ? AND resolver_version = ? AND candidate_key = ? AND entity_id = ?",
                (rs, row) -> rs.getBoolean(1), kbId, resolverVersion, candidateKey, entityId)
                .stream().findFirst();
    }

    @Override
    public void saveMergeDecision(long kbId, String resolverVersion, String candidateKey, String entityId,
                                  boolean sameEntity, String judge, String reason) {
        jdbc.update("INSERT IGNORE INTO graph_entity_merge_decision "
                        + "(kb_id, resolver_version, candidate_key, entity_id, same_entity, judge, reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                kbId, resolverVersion, candidateKey, entityId, sameEntity,
                bounded(judge == null ? "unknown" : judge, 200), bounded(reason, 500));
    }

    private RowMapper<RegisteredEntity> rowMapper() {
        return (rs, row) -> new RegisteredEntity(rs.getString("entity_id"), rs.getString("canonical_name"),
                GraphEntityType.valueOf(rs.getString("entity_type")), aliases(rs.getString("aliases_json")),
                rs.getString("context_snippet"));
    }

    private List<String> aliases(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String aliasesJson(List<String> aliases) {
        try {
            return objectMapper.writeValueAsString(aliases == null ? List.of() : aliases);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("实体别名无法序列化", e);
        }
    }

    private static String bounded(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    static String stableEntityId(long kbId, GraphEntityType type, String normalizedName, String resolverVersion) {
        try {
            String value = kbId + "|" + type + "|" + normalizedName + "|" + resolverVersion;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "e_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成实体身份", e);
        }
    }
}
