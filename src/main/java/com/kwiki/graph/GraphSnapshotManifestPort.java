package com.kwiki.graph;

import java.util.List;

/** 读取快照冻结来源清单（资源粒度）的端口，供全来源覆盖证明使用。 */
public interface GraphSnapshotManifestPort {

    /** 指定快照来源清单中的去重资源身份，确定性排序。 */
    List<GraphResourceId> manifestResources(long snapshotId);
}
