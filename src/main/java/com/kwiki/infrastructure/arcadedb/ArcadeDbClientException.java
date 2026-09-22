package com.kwiki.infrastructure.arcadedb;

/** 不含凭据和完整请求正文的 ArcadeDB 适配器异常。 */
public class ArcadeDbClientException extends RuntimeException {

    private final ArcadeDbFailureCategory category;
    private final Integer statusCode;

    public ArcadeDbClientException(ArcadeDbFailureCategory category, String message,
                                  Integer statusCode, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.statusCode = statusCode;
    }

    public ArcadeDbFailureCategory category() {
        return category;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
