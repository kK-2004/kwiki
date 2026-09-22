package com.kwiki.graph;

/** 抽取结构、来源或授权边界校验失败。 */
public class GraphExtractionValidationException extends RuntimeException {

    public GraphExtractionValidationException(String message) {
        super(message);
    }
}
