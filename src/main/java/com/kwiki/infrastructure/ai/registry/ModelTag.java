package com.kwiki.infrastructure.ai.registry;

import java.util.Locale;

/** 模型能力标签：决定一个模型可以被分配给哪些用途。 */
public enum ModelTag {
    /** 文本对话（含 tool calling）。 */
    CHAT,
    /** 图片理解。 */
    VISION,
    /** 向量嵌入；带此标签的模型必须登记维度。 */
    EMBEDDING;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ModelTag fromDb(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
