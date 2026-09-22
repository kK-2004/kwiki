package com.kwiki.graph;

import com.kwiki.graph.config.GraphProperties;

/** 超限明确阻止构建，不通过丢弃孤点或来源来伪造全量成功。 */
public final class GraphCapacityGuard {

    public record Counts(long entities, long relations, long relationSources,
                         long communities) {}

    public record Decision(boolean allowed, boolean warning, String reason) {}

    public Decision check(Counts counts, GraphProperties.Capacity capacity) {
        if (counts == null || capacity == null) {
            return new Decision(false, false, "capacity-configuration-missing");
        }
        if (counts.entities() > capacity.maxEntities()) {
            return new Decision(false, false, "CAPACITY_EXCEEDED_ENTITIES");
        }
        if (counts.relations() > capacity.maxRelations()) {
            return new Decision(false, false, "CAPACITY_EXCEEDED_RELATIONS");
        }
        if (counts.relationSources() > capacity.maxRelationSources()) {
            return new Decision(false, false, "CAPACITY_EXCEEDED_RELATION_SOURCES");
        }
        if (counts.communities() > capacity.maxCommunities()) {
            return new Decision(false, false, "CAPACITY_EXCEEDED_COMMUNITIES");
        }
        return new Decision(true, counts.entities() >= capacity.warnEntities()
                || counts.relations() >= capacity.warnRelations(), null);
    }
}
