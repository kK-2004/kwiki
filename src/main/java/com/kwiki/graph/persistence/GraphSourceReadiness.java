package com.kwiki.graph.persistence;

/** 来源清单中 CHILD 目标的可用性。 */
public enum GraphSourceReadiness {
    READY,
    WAITING_FOR_CHUNKS,
    MISSING,
    UNSUPPORTED,
    STALE
}
