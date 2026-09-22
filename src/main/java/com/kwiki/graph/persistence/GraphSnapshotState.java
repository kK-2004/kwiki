package com.kwiki.graph.persistence;

/** 图快照的不可变边界状态。 */
public enum GraphSnapshotState {
    BUILDING,
    READY,
    PUBLISHED,
    RETIRED,
    DELETING,
    FAILED
}
