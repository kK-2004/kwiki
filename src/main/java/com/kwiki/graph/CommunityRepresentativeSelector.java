package com.kwiki.graph;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 按稳定身份选择有界摘要输入，不把整个社区装入模型上下文。 */
public final class CommunityRepresentativeSelector {

    public static final int MAX_ENTITIES = 20;
    public static final int MAX_RELATIONS = 30;
    public static final int MAX_SOURCES = 8;
    public static final int MAX_CONTENT_CHARS = 12_000;

    private CommunityRepresentativeSelector() {
    }

    public static CommunitySummaryInput select(long kbId, long graphVersion, String communityId,
                                               List<GraphEntity> entities,
                                               List<GraphRelation> relations,
                                               List<GraphSourceChunk> sources,
                                               String content, String promptVersion) {
        Map<String, Integer> degree = new LinkedHashMap<>();
        Map<String, Set<String>> supports = new LinkedHashMap<>();
        for (GraphEntity entity : entities == null ? List.<GraphEntity>of() : entities) {
            degree.put(entity.entityId(), 0);
            supports.put(entity.entityId(), new java.util.HashSet<>());
        }
        for (GraphRelation relation : relations == null ? List.<GraphRelation>of() : relations) {
            degree.computeIfPresent(relation.sourceEntityId(), (key, value) -> value + 1);
            degree.computeIfPresent(relation.targetEntityId(), (key, value) -> value + 1);
            relation.sourceRefs().forEach(ref -> {
                supports.computeIfAbsent(relation.sourceEntityId(), ignored -> new java.util.HashSet<>())
                        .add(ref.sourceChunkId());
                supports.computeIfAbsent(relation.targetEntityId(), ignored -> new java.util.HashSet<>())
                        .add(ref.sourceChunkId());
            });
        }
        Comparator<GraphEntity> entityOrder = Comparator
                .comparingInt((GraphEntity entity) -> degree.getOrDefault(entity.entityId(), 0))
                .reversed()
                .thenComparing(Comparator.comparingInt((GraphEntity entity) ->
                        supports.getOrDefault(entity.entityId(), Set.of()).size()).reversed())
                .thenComparing(GraphEntity::entityId);
        List<GraphEntity> selectedEntities = (entities == null ? List.<GraphEntity>of() : entities)
                .stream().sorted(entityOrder).limit(MAX_ENTITIES).toList();

        Comparator<GraphRelation> relationOrder = Comparator
                .comparingInt((GraphRelation relation) -> relation.sourceRefs().size()).reversed()
                .thenComparing(GraphRelation::relationId);
        List<GraphRelation> selectedRelations = (relations == null ? List.<GraphRelation>of() : relations)
                .stream().sorted(relationOrder).limit(MAX_RELATIONS).toList();

        Set<String> sourceIds = selectedRelations.stream().flatMap(relation -> relation.sourceRefs().stream())
                .map(GraphSourceRef::sourceChunkId).collect(Collectors.toSet());
        List<GraphSourceChunk> selectedSources = (sources == null ? List.<GraphSourceChunk>of() : sources)
                .stream().filter(source -> sourceIds.contains(source.sourceChunkId()))
                .sorted(Comparator.comparing(GraphSourceChunk::sourceChunkId))
                .limit(MAX_SOURCES).toList();
        return new CommunitySummaryInput(kbId, graphVersion, communityId, selectedEntities,
                selectedRelations, selectedSources, truncate(content), promptVersion);
    }

    private static String truncate(String content) {
        if (content == null || content.length() <= MAX_CONTENT_CHARS) {
            return content == null ? "" : content;
        }
        return content.substring(0, MAX_CONTENT_CHARS);
    }
}
