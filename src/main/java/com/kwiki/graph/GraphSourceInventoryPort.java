package com.kwiki.graph;

import java.util.List;

/**
 * 授权与来源清单的读取端口：实现委托现有授权/清单存储，不引入新权限模型。
 * canRead 必须按资源粒度核验；角色名、kbId 可访问或代表 Chunk 可读不能替代。
 */
public interface GraphSourceInventoryPort {

    /** 知识库当前全部有效可索引资源，确定性排序去重。 */
    List<GraphResourceId> listCurrentSources(long kbId);

    /** 用户当前能否读取该资源；superuser 仍需真实资源存在。 */
    boolean canRead(long userId, boolean superuser, GraphResourceId resource);

    /** 知识库当前 content/security epoch。 */
    long[] currentEpochs(long kbId);
}
