package com.kwiki.graph.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个图构建离线模型端点：客户端、全局限流器与重试预算的组合。
 * 客户端在 GraphModelClients 构造器中创建，不注册为 Spring Bean。
 *
 * <p>{@code streaming} 与 {@code streamingPreferred} 用于网关超时（如 Cloudflare 524）后的自适应切换：
 * 同一端点一旦遇到 524，此后改用流式调用（标记在 GraphModelClients 里按模型共享，进程内有效）。
 * 二者为空表示不支持切换，始终非流式。
 */
public record ModelEndpoint(ChatModel model, RateLimiter limiter,
                            int maxRetries, String modelName,
                            StreamingChatModel streaming, Duration timeout,
                            AtomicBoolean streamingPreferred) {

    public ModelEndpoint {
        if (model == null || limiter == null || maxRetries < 0
                || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("模型端点配置无效");
        }
    }

    public ModelEndpoint(ChatModel model, RateLimiter limiter, int maxRetries, String modelName) {
        this(model, limiter, maxRetries, modelName, null, null, null);
    }

    /** 该端点是否已切换为流式调用。 */
    public boolean prefersStreaming() {
        return streaming != null && streamingPreferred != null && streamingPreferred.get();
    }

    /** 标记此后改用流式；返回是否为首次切换（用于只记一次日志）。 */
    public boolean switchToStreaming() {
        return streaming != null && streamingPreferred != null && streamingPreferred.compareAndSet(false, true);
    }
}
