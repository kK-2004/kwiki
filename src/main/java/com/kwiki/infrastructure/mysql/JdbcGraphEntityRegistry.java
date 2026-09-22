package com.kwiki.infrastructure.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityRegistryPort;
import com.kwiki.graph.GraphEntityType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 以 MySQL 唯一约束承载知识库内实体注册，歧义上下文不强行合并。 */
@Repository
@ConditionalOnBean(JdbcTemplate.class)
public class JdbcGraphEntityRegistry implements GraphEntityRegistryPort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGraphEntityRegistry(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public GraphEntity findOrRegister(long kbId, GraphEntity candidate,
                                      String contextFingerprint, String resolverVersion) {
        String existingId = jdbc.query("SELECT entity_id FROM graph_entity_registry "
                        + "WHERE kb_id = ? AND canonical_name = ? AND entity_type = ? "
                        + "AND context_hash = ? AND resolver_version = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                kbId, candidate.canonicalName(), candidate.entityType().name(),
                contextFingerprint, resolverVersion);
        String entityId = existingId == null
                ? stableEntityId(kbId, candidate, contextFingerprint, resolverVersion)
                : existingId;
        if (existingId == null) {
            jdbc.update("INSERT IGNORE INTO graph_entity_registry "
                            + "(kb_id, entity_id, canonical_name, entity_type, aliases_json, context_hash, resolver_version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    kbId, entityId, candidate.canonicalName(), candidate.entityType().name(),
                    aliasesJson(candidate.aliases()), contextFingerprint, resolverVersion);
            existingId = jdbc.query("SELECT entity_id FROM graph_entity_registry "
                            + "WHERE kb_id = ? AND canonical_name = ? AND entity_type = ? "
                            + "AND context_hash = ? AND resolver_version = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    kbId, candidate.canonicalName(), candidate.entityType().name(),
                    contextFingerprint, resolverVersion);
            entityId = existingId == null ? entityId : existingId;
        }
        return candidate.withEntityId(entityId);
    }

    private String aliasesJson(List<String> aliases) {
        try {
            return objectMapper.writeValueAsString(aliases == null ? List.of() : aliases);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("实体别名无法序列化", e);
        }
    }

    private static String stableEntityId(long kbId, GraphEntity candidate,
                                         String contextFingerprint, String resolverVersion) {
        try {
            String value = kbId + "|" + candidate.entityType() + "|" + candidate.canonicalName()
                    + "|" + contextFingerprint + "|" + resolverVersion;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "e_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成实体身份", e);
        }
    }
}
