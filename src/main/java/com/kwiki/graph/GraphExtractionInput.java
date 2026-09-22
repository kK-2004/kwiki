package com.kwiki.graph;

/** 实体关系抽取端口的输入，内容上下文由调用方按授权和来源边界准备。 */
public record GraphExtractionInput(
        GraphSourceChunk sourceChunk,
        String content,
        String parentContext,
        String extractorVersion,
        String promptVersion,
        String entityLinkingVersion) {

    public GraphExtractionInput {
        if (sourceChunk == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("抽取来源和内容不能为空");
        }
        if (extractorVersion == null || extractorVersion.isBlank()
                || promptVersion == null || promptVersion.isBlank()
                || entityLinkingVersion == null || entityLinkingVersion.isBlank()) {
            throw new IllegalArgumentException("抽取版本不能为空");
        }
        parentContext = parentContext == null ? "" : parentContext;
    }
}
