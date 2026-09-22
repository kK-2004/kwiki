package com.kwiki.graph;

/** 实体关系抽取端口，模型和提示词实现位于基础设施层。 */
public interface EntityRelationExtractionPort {

    GraphExtractionResult extract(GraphExtractionInput input);
}
