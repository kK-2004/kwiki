package com.kwiki.infrastructure.ai.registry;

/**
 * 可独立分配模型的用途。默认参数与迁移前各 Bean 的硬编码一致：
 * 非流式用途 temperature 0.0，回答与划词问答 0.2。
 * embedding 不在此列：它随索引版本绑定，不能全局热切换。
 */
public enum ModelPurpose {
    AGENT_PLANNER("Agent planner", "多工具规划：决定下一步调用哪些检索工具", ModelTag.CHAT, 0.0, true),
    RETRIEVAL_PLANNER("检索规划", "单工具检索规划（es_search）", ModelTag.CHAT, 0.0, true),
    ROUTER("意图路由", "判断意图、是否需要检索与改写方式", ModelTag.CHAT, 0.0, false),
    REWRITE("查询改写", "对用户问题做会话补全、扩展或拆分", ModelTag.CHAT, 0.0, false),
    DIRECT_ANSWER("直接回答", "无需检索时的直答", ModelTag.CHAT, 0.0, false),
    QUALITY_ANALYZER("质量分析", "评估证据是否充分（v1/v2 共用）", ModelTag.CHAT, 0.0, false),
    ANSWER("回答生成", "基于证据的流式回答", ModelTag.CHAT, 0.2, false),
    SELECTION_QUESTION("划词问答", "文档划词提问的流式回答", ModelTag.CHAT, 0.2, false),
    GRAPH_EXTRACTION("图谱抽取", "知识图谱实体与关系抽取", ModelTag.CHAT, 0.0, false),
    GRAPH_SUMMARY("社区摘要", "知识图谱社区摘要", ModelTag.CHAT, 0.0, false),
    VISION_SUMMARY("图片摘要", "多模态索引中的图片摘要", ModelTag.VISION, 0.0, false);

    private final String label;
    private final String description;
    private final ModelTag requiredTag;
    private final double defaultTemperature;
    private final boolean requiresTools;

    ModelPurpose(String label, String description, ModelTag requiredTag,
                 double defaultTemperature, boolean requiresTools) {
        this.label = label;
        this.description = description;
        this.requiredTag = requiredTag;
        this.defaultTemperature = defaultTemperature;
        this.requiresTools = requiresTools;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public ModelTag requiredTag() {
        return requiredTag;
    }

    public double defaultTemperature() {
        return defaultTemperature;
    }

    /** 仅在 kwiki.agentic.tool-mode=native 时要求所选模型支持原生 tool calling。 */
    public boolean requiresTools() {
        return requiresTools;
    }
}
