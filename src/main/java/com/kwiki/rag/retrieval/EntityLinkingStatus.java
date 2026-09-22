package com.kwiki.rag.retrieval;

/** CHILD 文档实体映射的生命周期状态。 */
public enum EntityLinkingStatus {
    PENDING,
    READY,
    FAILED,
    MISSING
}
