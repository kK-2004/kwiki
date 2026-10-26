package com.kwiki.usage;

import com.kwiki.infrastructure.ai.registry.ModelPurpose;

/** 用量步骤：对话模型用 {@link ModelPurpose} 枚举名，embedding 用本类两个常量。 */
public final class UsageSteps {

    public static final String QUERY_EMBEDDING = "QUERY_EMBEDDING";
    public static final String INDEX_EMBEDDING = "INDEX_EMBEDDING";
    /** 已下线用途在历史用量记录里仍可能出现，保留中文名。 */
    private static final java.util.Map<String, String> RETIRED = java.util.Map.of("SELECTION_QUESTION", "划词问答（已下线）");

    private UsageSteps() {
    }

    /** 步骤中文名；未知步骤原样返回，null 返回 unknown。 */
    public static String label(String step) {
        if (step == null) return "unknown";
        if (QUERY_EMBEDDING.equals(step)) return "查询向量";
        if (INDEX_EMBEDDING.equals(step)) return "索引向量";
        if (RETIRED.containsKey(step)) return RETIRED.get(step);
        try {
            return ModelPurpose.valueOf(step).label();
        } catch (IllegalArgumentException e) {
            return step;
        }
    }
}
