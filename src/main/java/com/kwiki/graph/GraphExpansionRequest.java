package com.kwiki.graph;

import java.util.List;

/** 有界社区内图扩展请求；不允许调用方传入任意 DSL 或可变长路径。 */
public record GraphExpansionRequest(long kbId, long graphVersion, String communityId,
                                    List<GraphSeed> seeds, int maxHops,
                                    int maxEdgesPerNode, int maxEdgesTotal,
                                    int maxEntities, int maxRelations) {

    /** seed-only 回退不限定单一社区；适配器仍按 kbId/版本/预算约束遍历。 */
    public static final String UNSCOPED_COMMUNITY = "*";

    public GraphExpansionRequest {
        if (kbId <= 0 || graphVersion <= 0 || communityId == null || communityId.isBlank()
                || maxHops < 1 || maxHops > 2 || maxEdgesPerNode < 1
                || maxEdgesTotal < 1 || maxEntities < 1 || maxRelations < 1) {
            throw new IllegalArgumentException("图扩展预算无效");
        }
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
    }
}
