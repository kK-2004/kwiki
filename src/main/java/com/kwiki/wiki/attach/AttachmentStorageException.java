package com.kwiki.wiki.attach;

/**
 * 由附件存储适配器抛出的、经过脱敏的存储异常。异常信息绝不
 * 包含应用令牌、授权头或签名 URL；其分类使得重试决策
 * （worker 退避 vs 终态死信）在不暴露提供方细节的前提下仍可表达。
 */
public class AttachmentStorageException extends RuntimeException {

    public enum Category {
        /** 值得重试：传输错误、超时、远端 429/5xx。 */
        TRANSIENT,
        /** 不值得重试：被拒请求、无效内容、不可用结果。 */
        PERMANENT
    }

    private final Category category;

    public AttachmentStorageException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public AttachmentStorageException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
