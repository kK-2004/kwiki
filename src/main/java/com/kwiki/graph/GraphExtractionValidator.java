package com.kwiki.graph;

import java.util.HashSet;
import java.util.Set;

/** 对模型已解析的抽取 DTO 执行来源和端点校验。 */
public final class GraphExtractionValidator {

    private GraphExtractionValidator() {
    }

    public static void validate(GraphExtractionInput input, GraphExtractionResult result) {
        if (input == null || result == null || !input.sourceChunk().equals(result.sourceChunk())) {
            throw new GraphExtractionValidationException("抽取结果来源身份不匹配");
        }
        if (!input.extractorVersion().equals(result.extractorVersion())
                || !input.promptVersion().equals(result.promptVersion())
                || !input.entityLinkingVersion().equals(result.entityLinkingVersion())) {
            throw new GraphExtractionValidationException("抽取结果版本不匹配");
        }
        Set<String> entityIds = new HashSet<>();
        for (GraphEntity entity : result.entities()) {
            if (entity.entityId() != null && !entityIds.add(entity.entityId())) {
                throw new GraphExtractionValidationException("抽取结果包含重复 entityId");
            }
        }
        String sourceChunkId = input.sourceChunk().sourceChunkId();
        for (GraphRelation relation : result.relations()) {
            if (!entityIds.contains(relation.sourceEntityId())
                    || !entityIds.contains(relation.targetEntityId())) {
                throw new GraphExtractionValidationException("关系端点不属于本次抽取实体集合");
            }
            for (GraphSourceRef ref : relation.sourceRefs()) {
                if (!sourceChunkId.equals(ref.sourceChunkId())
                        || ref.endOffset() > input.content().length()) {
                    throw new GraphExtractionValidationException("关系引文不属于真实输入 Chunk");
                }
            }
        }
    }
}
