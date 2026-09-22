package com.kwiki.graph.persistence;

/** 图构建阶段；状态和阶段分别持久化，便于恢复和展示。 */
public enum GraphBuildStage {
    QUEUED,
    EXTRACTING,
    PROJECTING,
    CLUSTERING,
    SUMMARIZING,
    INDEXING,
    VALIDATING,
    READY,
    PUBLISHED,
    CLEANING
}
