package com.kwiki.graph;

/** 版本化的实体关系抽取提示词身份。 */
public final class GraphExtractionPrompt {

    public static final String VERSION = "graph-extraction-v1";
    public static final String TEXT = "从给定的已发布知识库 Chunk 中抽取有原文支持的实体和语义关系。"
            + "只使用输入中的事实、来源位置和受控实体/关系类型；不要创建数据库类型、执行查询或猜测授权范围。"
            + "每条关系必须引用输入 Chunk 的真实字符区间，无法确认的关系不要输出。";

    private GraphExtractionPrompt() {
    }
}
