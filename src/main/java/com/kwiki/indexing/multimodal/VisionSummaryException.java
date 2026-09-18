package com.kwiki.indexing.multimodal;

/**
 * 视觉摘要失败（已脱敏）：分类决定索引 worker 的重试语义。
 * 消息绝不包含 API Key、完整 CDN URL 或请求/响应体。
 */
public class VisionSummaryException extends RuntimeException {

    public enum Category {
        /** 值得重试：429/5xx、超时、传输错误重试耗尽。 */
        TRANSIENT,
        /** 不值得重试：非重试 4xx、空/畸形/违规响应。 */
        PERMANENT
    }

    private final Category category;

    public VisionSummaryException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public VisionSummaryException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
