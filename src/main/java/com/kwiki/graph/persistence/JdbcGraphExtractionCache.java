package com.kwiki.graph.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * graph_extraction_result 表上的抽取缓存：按（来源块、抽取器、提示词、
 * 实体链接版本）唯一身份读取 READY 结果，写回实体与关系 JSON。
 */
@Repository
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class JdbcGraphExtractionCache implements GraphExtractionCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<GraphEntity>> ENTITY_LIST =
            new TypeReference<>() {
            };
    private static final TypeReference<List<GraphRelation>> RELATION_LIST =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;

    public JdbcGraphExtractionCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GraphExtractionResult> findReady(GraphSourceChunk source, String extractorVersion,
                                                     String promptVersion, String entityLinkingVersion) {
        return jdbc.query("SELECT entities_json, relations_json FROM graph_extraction_result "
                        + "WHERE source_chunk_id = ? AND extractor_version = ? AND prompt_version = ? "
                        + "AND entity_linking_version = ? AND state = 'READY'",
                rs -> rs.next() ? Optional.of(new GraphExtractionResult(source,
                        deserialize(rs.getString(1), ENTITY_LIST, "entities_json"),
                        deserialize(rs.getString(2), RELATION_LIST, "relations_json"),
                        extractorVersion, promptVersion, entityLinkingVersion))
                        : Optional.empty(),
                source.sourceChunkId(), extractorVersion, promptVersion, entityLinkingVersion);
    }

    @Override
    public void markReady(long extractionId, GraphExtractionResult result) {
        jdbc.update("UPDATE graph_extraction_result SET entities_json = ?, relations_json = ?, "
                        + "state = 'READY', last_error_class = NULL, last_error_summary = NULL "
                        + "WHERE id = ?",
                serialize(result.entities()), serialize(result.relations()), extractionId);
    }

    @Override
    public void markFailed(long extractionId, String errorClass, String errorSummary) {
        jdbc.update("UPDATE graph_extraction_result SET state = 'FAILED', "
                        + "last_error_class = ?, last_error_summary = ? WHERE id = ?",
                truncate(errorClass, 200), truncate(errorSummary, 1000), extractionId);
    }

    private static <T> List<T> deserialize(String json, TypeReference<List<T>> type, String field) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "[]" : json, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("抽取缓存字段 " + field + " 无法反序列化", failure);
        }
    }

    private static String serialize(List<?> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("抽取缓存结果无法序列化", failure);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
