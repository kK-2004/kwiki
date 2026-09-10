package com.kwiki.rag.orchestration;

/**
 * Deterministic recovery stages of the QA-gated knowledge answer path. Every
 * query round walks these stages in order and each candidate answer is
 * regenerated and re-evaluated from scratch; any stage that passes the QA gate
 * stops the progression immediately.
 */
public enum AttemptStage {
    BASE_CHILD("基础检索"),
    BASE_PARENT("补充上下文"),
    EXPANDED_CHILD("扩大检索"),
    EXPANDED_PARENT("扩大后补充上下文");

    private final String label;

    AttemptStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isParentStage() {
        return this == BASE_PARENT || this == EXPANDED_PARENT;
    }

    public boolean isExpandedStage() {
        return this == EXPANDED_CHILD || this == EXPANDED_PARENT;
    }

    /** Next stage in the fixed order, or null when the query round is exhausted. */
    public AttemptStage next() {
        return switch (this) {
            case BASE_CHILD -> BASE_PARENT;
            case BASE_PARENT -> EXPANDED_CHILD;
            case EXPANDED_CHILD -> EXPANDED_PARENT;
            case EXPANDED_PARENT -> null;
        };
    }
}
