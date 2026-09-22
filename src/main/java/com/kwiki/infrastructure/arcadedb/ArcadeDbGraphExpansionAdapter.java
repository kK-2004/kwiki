package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.GraphExpansionPort;
import com.kwiki.graph.GraphExpansionRequest;
import com.kwiki.graph.GraphExpansionResult;
import com.kwiki.graph.GraphPredicate;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSeed;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** ArcadeDB 有界邻接查询；服务端先按版本/社区/预算过滤，再返回有限结果。 */
@Component
@ConditionalOnBean(ArcadeDbHttpAdapter.class)
public class ArcadeDbGraphExpansionAdapter implements GraphExpansionPort {

    private static final String ENTITY_QUERY = """
            SELECT entityId, canonicalName, entityType FROM Entity
            WHERE kbId = :kbId AND graphVersion = :graphVersion
              AND communityId = :communityId AND entityId IN :seedIds
            LIMIT :maxEntities
            """;
    private static final String RELATION_QUERY = """
            SELECT relationId, sourceEntityId, targetEntityId, predicate, sourceChunkId,
                   startOffset, endOffset FROM RelationEvidence
            WHERE kbId = :kbId AND graphVersion = :graphVersion
              AND sourceEntityId = :sourceEntityId
            LIMIT :maxEdgesPerNode
            """;

    private final ArcadeDbHttpAdapter adapter;
    private final ArcadeDbProperties properties;
    private final ObjectMapper mapper;

    public ArcadeDbGraphExpansionAdapter(ArcadeDbHttpAdapter adapter,
                                         ArcadeDbProperties properties,
                                         ObjectMapper mapper) {
        this.adapter = adapter;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public GraphExpansionResult expand(GraphExpansionRequest request) {
        try {
            List<String> initial = request.seeds().stream().map(GraphSeed::entityId)
                    .distinct().limit(request.maxEntities()).toList();
            if (initial.isEmpty()) return new GraphExpansionResult(List.of(), List.of(), 0, false);
            java.util.LinkedHashSet<String> discovered = new java.util.LinkedHashSet<>(initial);
            java.util.LinkedHashSet<String> frontier = new java.util.LinkedHashSet<>(initial);
            List<GraphRelation> relations = new ArrayList<>();
            int checks = 0;
            boolean truncated = false;
            for (int hop = 0; hop < request.maxHops() && !frontier.isEmpty(); hop++) {
                java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>();
                for (String sourceEntityId : frontier) {
                    if (checks >= request.maxEdgesTotal()) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> params = Map.of("kbId", request.kbId(),
                            "graphVersion", request.graphVersion(), "communityId", request.communityId(),
                            "sourceEntityId", sourceEntityId,
                            "maxEdgesPerNode", Math.min(request.maxEdgesPerNode(),
                                    request.maxEdgesTotal() - checks));
                    var response = adapter.query(properties.database(), RELATION_QUERY, params);
                    JsonNode relationRows = rows(mapper.readTree(response.body()));
                    if (!relationRows.isArray()) continue;
                    if (relationRows.size() >= request.maxEdgesPerNode()) truncated = true;
                    for (JsonNode row : relationRows) {
                        if (checks >= request.maxEdgesTotal()) {
                            truncated = true;
                            break;
                        }
                        checks++;
                        String target = text(row, "targetEntityId");
                        String sourceChunk = text(row, "sourceChunkId");
                        if (target == null || sourceChunk == null || target.equals(sourceEntityId)) continue;
                        try {
                            GraphRelation relation = new GraphRelation(text(row, "relationId"), sourceEntityId,
                                    GraphPredicate.valueOf(text(row, "predicate")), target, 1,
                                    List.of(new GraphSourceRef(sourceChunk,
                                            integer(row, "startOffset"), integer(row, "endOffset"))));
                            String identity = relation.relationId() + "\u0000" + sourceEntityId
                                    + "\u0000" + target;
                            if (relations.stream().noneMatch(existing ->
                                    (existing.relationId() + "\u0000" + existing.sourceEntityId()
                                            + "\u0000" + existing.targetEntityId()).equals(identity))) {
                                relations.add(relation);
                            }
                            if (discovered.add(target)) next.add(target);
                        } catch (RuntimeException ignored) {
                            // 非法或受控枚举外的关系不能进入在线增强结果。
                        }
                    }
                }
                frontier = next;
                if (discovered.size() >= request.maxEntities()) {
                    truncated = true;
                    break;
                }
            }
            List<String> entityIds = discovered.stream().limit(request.maxEntities()).toList();
            Map<String, Object> entityParams = Map.of("kbId", request.kbId(),
                    "graphVersion", request.graphVersion(), "communityId", request.communityId(),
                    "seedIds", entityIds, "maxEntities", request.maxEntities(),
                    "maxEdges", request.maxEdgesTotal());
            var entitiesResponse = adapter.query(properties.database(), ENTITY_QUERY, entityParams);
            JsonNode entityRows = rows(mapper.readTree(entitiesResponse.body()));
            List<GraphEntity> entities = new ArrayList<>();
            if (entityRows.isArray()) for (JsonNode row : entityRows) {
                String id = text(row, "entityId");
                if (id == null) continue;
                GraphEntityType type = parseType(text(row, "entityType"));
                String canonicalName = text(row, "canonicalName");
                entities.add(new GraphEntity(id,
                        canonicalName == null || canonicalName.isBlank() ? id : canonicalName,
                        type, List.of()));
            }
            java.util.Set<String> visibleEntities = entities.stream().map(GraphEntity::entityId)
                    .collect(java.util.stream.Collectors.toSet());
            relations.removeIf(relation -> !visibleEntities.contains(relation.sourceEntityId())
                    || !visibleEntities.contains(relation.targetEntityId()));
            if (relations.size() > request.maxRelations()) {
                truncated = true;
                relations = new ArrayList<>(relations.subList(0, request.maxRelations()));
            }
            return new GraphExpansionResult(entities.stream().limit(request.maxEntities()).toList(),
                    relations, checks, truncated || checks >= request.maxEdgesTotal());
        } catch (Exception failure) {
            throw new IllegalStateException("ArcadeDB 图扩展结果无法解析", failure);
        }
    }

    private static JsonNode rows(JsonNode root) { return root.isArray() ? root : root.path("result"); }
    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private static int integer(JsonNode row, String field) { return row.path(field).asInt(0); }
    private static GraphEntityType parseType(String value) {
        if (value == null) return GraphEntityType.OTHER;
        try { return GraphEntityType.valueOf(value); } catch (IllegalArgumentException ignored) {
            return GraphEntityType.OTHER;
        }
    }
}
