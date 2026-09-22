package com.kwiki.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 将正式有向关系归一化为无序、去自环、去重的无权实体投影。 */
public final class UnweightedGraphProjectionBuilder {

    private UnweightedGraphProjectionBuilder() {
    }

    public static UnweightedGraphProjection build(long kbId, long graphVersion,
                                                  List<GraphEntity> entities,
                                                  List<GraphRelation> relations) {
        Set<String> entityIds = new java.util.TreeSet<>();
        if (entities != null) {
            entities.stream().map(GraphEntity::entityId).forEach(entityIds::add);
        }
        Set<UnweightedGraphProjection.Edge> edges = new LinkedHashSet<>();
        if (relations != null) {
            for (GraphRelation relation : relations) {
                String source = relation.sourceEntityId();
                String target = relation.targetEntityId();
                if (!entityIds.contains(source) || !entityIds.contains(target) || source.equals(target)) {
                    continue;
                }
                String left = source.compareTo(target) < 0 ? source : target;
                String right = source.compareTo(target) < 0 ? target : source;
                edges.add(new UnweightedGraphProjection.Edge(left, right));
            }
        }
        List<String> sortedEntities = List.copyOf(entityIds);
        List<UnweightedGraphProjection.Edge> sortedEdges = edges.stream()
                .sorted(java.util.Comparator.comparing(UnweightedGraphProjection.Edge::leftEntityId)
                        .thenComparing(UnweightedGraphProjection.Edge::rightEntityId))
                .toList();
        return new UnweightedGraphProjection(kbId, graphVersion, sortedEntities, sortedEdges,
                UnweightedGraphProjection.hash(kbId, graphVersion, sortedEntities, sortedEdges));
    }
}
