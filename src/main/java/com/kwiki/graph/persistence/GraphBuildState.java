package com.kwiki.graph.persistence;

/** 图构建批次/子任务的持久化状态。 */
public enum GraphBuildState {
    QUEUED,
    EXTRACTING,
    PROJECTING,
    CLUSTERING,
    SUMMARIZING,
    INDEXING,
    VALIDATING,
    READY,
    PUBLISHED,
    PARTIAL_FAILED,
    FAILED,
    CANCELLED,
    STALE,
    WAITING_FOR_CHUNKS,
    UNSUPPORTED,
    NEEDS_ATTENTION
}
