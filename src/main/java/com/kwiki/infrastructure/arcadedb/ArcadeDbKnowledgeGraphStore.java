package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphWriteRequest;
import com.kwiki.graph.GraphWriteResult;
import com.kwiki.graph.KnowledgeGraphStore;
import com.kwiki.graph.config.ArcadeDbProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ArcadeDB 图投影写入适配器。业务键、kbId、graphVersion、runId 和 fencing
 * token 始终作为参数传递；重复 UPSERT 不累加关系来源支持数。
 */
public final class ArcadeDbKnowledgeGraphStore implements KnowledgeGraphStore {

    private static final String UPSERT_SOURCE = "UPDATE Chunk SET kbId = :kbId, graphVersion = :graphVersion, "
            + "sourceChunkId = :sourceChunkId, resourceType = :resourceType, resourceId = :resourceId, "
            + "revisionId = :revisionId, lifecycleVersion = :lifecycleVersion, chunkKey = :chunkKey, "
            + "contentHash = :contentHash, parserVersion = :parserVersion, chunkerVersion = :chunkerVersion "
            + "UPSERT WHERE kbId = :kbId AND graphVersion = :graphVersion AND sourceChunkId = :sourceChunkId";
    private static final String UPSERT_ENTITY = "UPDATE Entity SET kbId = :kbId, graphVersion = :graphVersion, "
            + "entityId = :entityId, canonicalName = :canonicalName, entityType = :entityType, aliases = :aliases "
            + "UPSERT WHERE kbId = :kbId AND graphVersion = :graphVersion AND entityId = :entityId";
    private static final String UPSERT_RELATION = "UPDATE RelationEvidence SET kbId = :kbId, graphVersion = :graphVersion, "
            + "evidenceId = :evidenceId, relationId = :relationId, sourceEntityId = :sourceEntityId, predicate = :predicate, "
            + "targetEntityId = :targetEntityId, confidence = :confidence, sourceChunkId = :sourceChunkId, "
            + "startOffset = :startOffset, endOffset = :endOffset, "
            + "runId = :runId, fencingToken = :fencingToken "
            + "UPSERT WHERE kbId = :kbId AND graphVersion = :graphVersion AND evidenceId = :evidenceId";

    private static final String ASSIGN_COMMUNITY = "UPDATE Entity SET communityId = :communityId "
            + "WHERE kbId = :kbId AND graphVersion = :graphVersion AND entityId IN :entityIds";

    private final ArcadeDbHttpAdapter client;
    private final ArcadeDbProperties properties;
    private final ObjectMapper objectMapper;
    private final ArcadeDbSchemaInitializer schemaInitializer;

    public ArcadeDbKnowledgeGraphStore(ArcadeDbHttpAdapter client,
                                       ArcadeDbProperties properties,
                                       ObjectMapper objectMapper,
                                       ArcadeDbSchemaInitializer schemaInitializer) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.schemaInitializer = schemaInitializer;
    }

    @Override
    public GraphWriteResult write(GraphWriteRequest request) {
        // 启动时 ArcadeDB 不可达会跳过初始化，这里在首次写入前补做
        schemaInitializer.ensureInitialized();
        int sourceCount = writeSources(request);
        int entityCount = writeEntities(request);
        RelationWriteCounts relationCounts = writeRelations(request);
        return new GraphWriteResult(sourceCount, entityCount, relationCounts.relations(),
                relationCounts.evidences(), false);
    }

    /** 把聚类结果回写到实体顶点，供检索时按社区做有界邻接扩展。 */
    @Override
    public void assignCommunities(long kbId, long graphVersion,
                                  List<com.kwiki.graph.CommunityMembership> memberships) {
        Map<String, List<String>> byCommunity = new java.util.TreeMap<>();
        for (var member : memberships) {
            byCommunity.computeIfAbsent(member.communityId(), ignored -> new ArrayList<>())
                    .add(member.entityId());
        }
        for (var entry : byCommunity.entrySet()) {
            List<String> entityIds = entry.getValue();
            for (int from = 0; from < entityIds.size(); from += ASSIGN_BATCH) {
                Map<String, Object> params = new HashMap<>();
                params.put("kbId", kbId);
                params.put("graphVersion", graphVersion);
                params.put("communityId", entry.getKey());
                params.put("entityIds", new ArrayList<>(entityIds.subList(from,
                        Math.min(entityIds.size(), from + ASSIGN_BATCH))));
                client.command(properties.database(), ASSIGN_COMMUNITY, params,
                        ArcadeDbTimeoutKind.BATCH_WRITE);
            }
        }
    }

    private static final int ASSIGN_BATCH = 500;

    /** 用固定查询计划入口供离线性能诊断使用。 */
    public ArcadeDbResponse explainEntityLookup(long kbId, long graphVersion, String entityId) {
        return client.query(properties.database(),
                "EXPLAIN SELECT FROM Entity WHERE kbId = :kbId AND graphVersion = :graphVersion "
                        + "AND entityId = :entityId LIMIT 1",
                Map.of("kbId", kbId, "graphVersion", graphVersion, "entityId", entityId));
    }

    private int writeSources(GraphWriteRequest request) {
        return writeBatches(request.sources(), UPSERT_SOURCE, source -> {
            Map<String, Object> params = new HashMap<>();
            params.put("kbId", request.kbId());
            params.put("graphVersion", request.graphVersion());
            params.put("sourceChunkId", source.sourceChunkId());
            params.put("resourceType", source.resourceType());
            params.put("resourceId", source.resourceId());
            params.put("revisionId", source.revisionId());
            params.put("lifecycleVersion", source.lifecycleVersion());
            params.put("chunkKey", source.chunkKey());
            params.put("contentHash", source.contentHash());
            params.put("parserVersion", source.parserVersion());
            params.put("chunkerVersion", source.chunkerVersion());
            return params;
        });
    }

    private int writeEntities(GraphWriteRequest request) {
        return writeBatches(request.entities(), UPSERT_ENTITY, entity -> {
            Map<String, Object> params = new HashMap<>();
            params.put("kbId", request.kbId());
            params.put("graphVersion", request.graphVersion());
            params.put("entityId", entity.entityId());
            params.put("canonicalName", entity.canonicalName());
            params.put("entityType", entity.entityType().name());
            params.put("aliases", new ArrayList<>(entity.aliases()));
            return params;
        });
    }

    private RelationWriteCounts writeRelations(GraphWriteRequest request) {
        List<RelationEvidenceWrite> evidence = new ArrayList<>();
        for (GraphRelation relation : request.relations()) {
            for (var sourceRef : relation.sourceRefs()) {
                evidence.add(new RelationEvidenceWrite(relation, sourceRef));
            }
        }
        int evidences = 0;
        for (List<RelationEvidenceWrite> batch : GraphWriteBatcher.split(evidence, objectMapper)) {
            for (RelationEvidenceWrite item : batch) {
                GraphRelation relation = item.relation();
                var sourceRef = item.sourceRef();
                Map<String, Object> params = new HashMap<>();
                params.put("kbId", request.kbId());
                params.put("graphVersion", request.graphVersion());
                params.put("evidenceId", evidenceId(relation, sourceRef));
                params.put("relationId", relation.relationId());
                params.put("sourceEntityId", relation.sourceEntityId());
                params.put("predicate", relation.predicate().name());
                params.put("targetEntityId", relation.targetEntityId());
                params.put("confidence", relation.confidence());
                params.put("sourceChunkId", sourceRef.sourceChunkId());
                params.put("startOffset", sourceRef.startOffset());
                params.put("endOffset", sourceRef.endOffset());
                params.put("runId", request.runId());
                params.put("fencingToken", request.fencingToken());
                client.command(properties.database(), UPSERT_RELATION, params,
                        ArcadeDbTimeoutKind.BATCH_WRITE);
                evidences++;
            }
        }
        return new RelationWriteCounts(request.relations().size(), evidences);
    }

    private static String evidenceId(GraphRelation relation, com.kwiki.graph.GraphSourceRef sourceRef) {
        return relation.relationId() + ":" + sourceRef.sourceChunkId()
                + ":" + sourceRef.startOffset() + ":" + sourceRef.endOffset();
    }

    private record RelationEvidenceWrite(GraphRelation relation, com.kwiki.graph.GraphSourceRef sourceRef) {
    }

    private record RelationWriteCounts(int relations, int evidences) {
    }

    private <T> int writeBatches(List<T> values, String statement,
                                 java.util.function.Function<T, Map<String, Object>> parameters) {
        int written = 0;
        for (List<T> batch : GraphWriteBatcher.split(values, objectMapper)) {
            for (T value : batch) {
                client.command(properties.database(), statement, parameters.apply(value),
                        ArcadeDbTimeoutKind.BATCH_WRITE);
                written++;
            }
        }
        return written;
    }
}
