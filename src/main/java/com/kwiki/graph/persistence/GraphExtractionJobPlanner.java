package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 从持久化内容事件规划独立图抽取目标。只有 CHILD 索引成功且来源仍是
 * 已发布身份时才入队；ArcadeDB 与 Elasticsearch 目标分别持久化，互不回滚。
 */
@Repository
@ConditionalOnBean(JdbcTemplate.class)
public class GraphExtractionJobPlanner {

    private final JdbcTemplate jdbc;

    public GraphExtractionJobPlanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<Long> plan(GraphExtractionJobSpec spec,
                               boolean childIndexSucceeded,
                               boolean publishedRevision) {
        if (!childIndexSucceeded || !publishedRevision) {
            return Optional.empty();
        }
        GraphSourceChunk source = spec.sourceChunk();
        jdbc.update("INSERT IGNORE INTO graph_extraction_result "
                        + "(kb_id, resource_type, resource_id, revision_id, lifecycle_version, index_version, "
                        + "parser_version, chunker_version, chunk_key, source_chunk_id, content_hash, "
                        + "extractor_version, prompt_version, entity_linking_version, content_epoch, security_epoch, "
                        + "entities_json, relations_json, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', '[]', 'PENDING')",
                source.kbId(), source.resourceType(), source.resourceId(), source.revisionId(),
                source.lifecycleVersion(), source.indexVersion(), source.parserVersion(), source.chunkerVersion(),
                source.chunkKey(), source.sourceChunkId(), source.contentHash(), spec.extractorVersion(),
                spec.promptVersion(), spec.entityLinkingVersion(), spec.contentEpoch(), spec.securityEpoch());
        Long extractionId = jdbc.queryForObject("SELECT id FROM graph_extraction_result "
                + "WHERE source_chunk_id = ? AND extractor_version = ? AND prompt_version = ? "
                + "AND entity_linking_version = ?", Long.class, source.sourceChunkId(),
                spec.extractorVersion(), spec.promptVersion(), spec.entityLinkingVersion());
        if (extractionId == null) {
            throw new IllegalStateException("图抽取结果入队后无法回读身份");
        }
        insertTarget(extractionId, "ARCADEDB", "arcadedb",
                targetKey(source, spec, "ARCADEDB"));
        insertTarget(extractionId, "ELASTICSEARCH", "elasticsearch",
                targetKey(source, spec, "ELASTICSEARCH"));
        return Optional.of(extractionId);
    }

    private void insertTarget(long extractionId, String targetKind, String targetIdentity,
                              String idempotencyKey) {
        jdbc.update("INSERT IGNORE INTO graph_extraction_target "
                        + "(extraction_id, target_kind, target_identity, idempotency_key, state) "
                        + "VALUES (?, ?, ?, ?, 'PENDING')",
                extractionId, targetKind, targetIdentity, idempotencyKey);
    }

    private static String targetKey(GraphSourceChunk source, GraphExtractionJobSpec spec,
                                    String kind) {
        return "graph:" + kind + ":" + source.sourceChunkId() + ":"
                + spec.extractorVersion() + ":" + spec.promptVersion() + ":"
                + spec.entityLinkingVersion();
    }
}
