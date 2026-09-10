package com.kwiki.rag.orchestration;

/**
 * QA 门控知识答案路径的确定性恢复阶段。每个
 * 查询轮次按顺序走过这些阶段，每个候选答案都
 * 从头重新生成并重新评估；任何通过 QA 门的阶段
 * 都会立即终止推进。
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

    /** 固定顺序中的下一个阶段；查询轮次耗尽时为 null。 */
    public AttemptStage next() {
        return switch (this) {
            case BASE_CHILD -> BASE_PARENT;
            case BASE_PARENT -> EXPANDED_CHILD;
            case EXPANDED_CHILD -> EXPANDED_PARENT;
            case EXPANDED_PARENT -> null;
        };
    }
}
