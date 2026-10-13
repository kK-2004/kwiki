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
    private final boolean contentMissing;

    public AttachmentStorageException(Category category, String message) {
        super(message);
        this.category = category;
        this.contentMissing = false;
    }

    public AttachmentStorageException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.contentMissing = false;
    }

    private AttachmentStorageException(String message, Throwable cause, boolean contentMissing) {
        super(message, cause);
        this.category = Category.PERMANENT;
        this.contentMissing = contentMissing;
    }

    /** 内容中心确认文件不存在：永久失败，需删除后重新上传。 */
    public static AttachmentStorageException contentMissing(String message, Throwable cause) {
        return new AttachmentStorageException(message, cause, true);
    }

    public boolean isContentMissing() {
        return contentMissing;
    }

    /** 沿异常链查找「内容已丢失」。 */
    public static boolean isContentMissing(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AttachmentStorageException storage && storage.isContentMissing()) {
                return true;
            }
        }
        return false;
    }

    public Category getCategory() {
        return category;
    }
}
