package com.kwiki.infrastructure.ai.registry;

import java.util.Map;

/** 一个用途的模型分配与调用参数；参数为 null 时取 {@link ModelPurpose} 的代码默认值，extra 覆盖模型上的同名项。 */
public record ModelAssignment(ModelPurpose purpose, Long modelId, Double temperature,
                              Integer maxTokens, Boolean thinking, Map<String, Object> extra) {

    public ModelAssignment {
        extra = extra == null ? Map.of() : extra;
    }

    public ModelAssignment(ModelPurpose purpose, Long modelId, Double temperature, Integer maxTokens,
                           Boolean thinking) {
        this(purpose, modelId, temperature, maxTokens, thinking, Map.of());
    }

    public static ModelAssignment unassigned(ModelPurpose purpose) {
        return new ModelAssignment(purpose, null, null, null, null);
    }
}
