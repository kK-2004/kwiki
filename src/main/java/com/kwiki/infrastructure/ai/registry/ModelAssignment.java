package com.kwiki.infrastructure.ai.registry;

/** 一个用途的模型分配与调用参数；参数为 null 时取 {@link ModelPurpose} 的代码默认值。 */
public record ModelAssignment(ModelPurpose purpose, Long modelId, Double temperature,
                              Integer maxTokens, Boolean thinking) {

    public static ModelAssignment unassigned(ModelPurpose purpose) {
        return new ModelAssignment(purpose, null, null, null, null);
    }
}
