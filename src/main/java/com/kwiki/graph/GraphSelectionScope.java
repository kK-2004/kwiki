package com.kwiki.graph;

import java.util.Set;

/** 用户本次请求选择的查询范围；有权限但只选部分资源时不能使用全库社区。 */
public record GraphSelectionScope(boolean entireKnowledgeBase,
                                  Set<GraphResourceId> selectedResources) {
    public GraphSelectionScope {
        selectedResources = Set.copyOf(selectedResources == null ? Set.of() : selectedResources);
        if (!entireKnowledgeBase && selectedResources.isEmpty()) {
            throw new IllegalArgumentException("非全库选择必须携带所选资源");
        }
    }

    public static GraphSelectionScope all() {
        return new GraphSelectionScope(true, Set.of());
    }

    public static GraphSelectionScope of(GraphResourceId... resources) {
        return new GraphSelectionScope(false, Set.of(resources));
    }

    public boolean covers(GraphResourceId resource) {
        return entireKnowledgeBase || selectedResources.contains(resource);
    }
}
