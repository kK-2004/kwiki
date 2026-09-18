package com.kwiki.indexing.multimodal;

/**
 * 外链图片抓取失败（已脱敏）：分类决定索引 worker 的重试语义。
 * 消息绝不包含原始/重定向 URL、内部地址或响应体。
 */
public class ExternalImageFetchException extends RuntimeException {

    public enum Category {
        /** 值得重试：传输错误、超时、远端 5xx。 */
        TRANSIENT,
        /** 不值得重试：策略拒绝、非图片、超限、非 HTTPS、过多重定向。 */
        PERMANENT
    }

    private final Category category;

    public ExternalImageFetchException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public ExternalImageFetchException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
