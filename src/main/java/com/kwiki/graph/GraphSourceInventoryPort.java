package com.kwiki.graph;

import java.util.List;

/**
 * 授权与来源清单的读取端口：实现委托现有授权/清单存储，不引入新权限模型。
 * canRead 必须按资源粒度核验；角色名、kbId 可访问或代表 Chunk 可读不能替代。
 */
public interface GraphSourceInventoryPort {

    /** 资源生命周期：LIVE 为可索引有效态（PAGE=ACTIVE、ATTACHMENT=STORED），其余一律 RETIRED。 */
    enum LifecycleState { LIVE, RETIRED }

    /** 知识库当前全部有效可索引资源，确定性排序去重。 */
    List<GraphResourceId> listCurrentSources(long kbId);

    /** 用户当前能否读取该资源；superuser 仍需真实资源存在。 */
    boolean canRead(long userId, boolean superuser, GraphResourceId resource);

    /** 知识库当前 content/security epoch。 */
    long[] currentEpochs(long kbId);

    /**
     * 批量生命周期判定：快照清单来源 LIVE/RETIRED 的唯一依据。
     * 行不存在（含知识库整库物理删除后）计为 RETIRED；实现每次调用最多一条 SQL。
     */
    java.util.Map<GraphResourceId, LifecycleState> lifecycleStates(List<GraphResourceId> resources);

    /** 资源行是否仍存在于业务库（任何生命周期状态）；硬删除的回滚与退役约束使用。 */
    boolean exists(GraphResourceId resource);

    /**
     * 批量版 {@link #exists}：返回仍存在于业务库（任何生命周期状态）的资源。
     * 快照清单可能有成千上万个来源，实现应按批查询；默认实现逐个调用 exists。
     */
    default java.util.Set<GraphResourceId> existingResources(List<GraphResourceId> resources) {
        java.util.Set<GraphResourceId> existing = new java.util.LinkedHashSet<>();
        if (resources != null) {
            for (GraphResourceId resource : resources) {
                if (exists(resource)) {
                    existing.add(resource);
                }
            }
        }
        return existing;
    }
}
