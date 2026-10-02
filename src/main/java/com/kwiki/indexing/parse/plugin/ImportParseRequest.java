package com.kwiki.indexing.parse.plugin;

/** 导入解析输入，携带目标知识库、文件信息和内容。 */
public record ImportParseRequest(long kbId, String fileName, String contentType, byte[] content) {
}
