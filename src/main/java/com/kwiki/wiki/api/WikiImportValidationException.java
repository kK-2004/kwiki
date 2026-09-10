package com.kwiki.wiki.api;

/** 仅由导入边界产生的安全消息，绝不来自外部服务。 */
public class WikiImportValidationException extends RuntimeException {
    public WikiImportValidationException(String message) { super(message); }
}
