package com.kwiki.graph.persistence;

/** 按快照资源清单删除外部资源的端口；实现由 ES/ArcadeDB 适配器提供。 */
public interface GraphSnapshotResourceCleanupPort {

    /**
     * 删除一种快照自有资源；返回 false 表示本次失败，资源行保持可重试状态。
     */
    boolean delete(String resourceKind, String resourceIdentity);
}
