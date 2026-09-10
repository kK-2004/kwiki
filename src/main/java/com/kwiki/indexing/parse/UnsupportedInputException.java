package com.kwiki.indexing.parse;

/**
 * 该输入不可索引：不在白名单内，或不携带可抽取的
 * 文本（绝不调用 OCR —— 此类文件在任何向量嵌入
 * 或索引写入之前就被拒绝）。
 */
public class UnsupportedInputException extends RuntimeException {

    public UnsupportedInputException(String message, Throwable cause) { super(message, cause); }

    public UnsupportedInputException(String message) {
        super(message);
    }
}
