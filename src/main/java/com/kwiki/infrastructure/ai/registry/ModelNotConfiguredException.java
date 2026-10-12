package com.kwiki.infrastructure.ai.registry;

/** 用途未分配模型，或所分配的模型 / provider 已停用。 */
public class ModelNotConfiguredException extends IllegalStateException {

    private final ModelPurpose purpose;

    public ModelNotConfiguredException(ModelPurpose purpose) {
        super(purpose.name() + "（" + purpose.label() + "）未配置可用模型");
        this.purpose = purpose;
    }

    public ModelPurpose purpose() {
        return purpose;
    }
}
