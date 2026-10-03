package com.kwiki.graph.persistence;

/** run 的 BUILDING 快照引用，用于崩溃恢复时复用已创建的清单与快照。 */
public record GraphBuildingSnapshot(long snapshotId, long manifestId, String manifestHash) {
    public GraphBuildingSnapshot {
        if (snapshotId <= 0 || manifestId <= 0
                || manifestHash == null || manifestHash.isBlank()) {
            throw new IllegalArgumentException("BUILDING 快照引用无效");
        }
    }
}
