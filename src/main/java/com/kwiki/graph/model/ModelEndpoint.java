package com.kwiki.graph.model;

import dev.langchain4j.model.chat.ChatModel;

/**
 * 一个图构建离线模型端点：客户端、全局限流器与重试预算的组合。
 * 客户端在 GraphModelClients 构造器中创建，不注册为 Spring Bean。
 */
public record ModelEndpoint(ChatModel model, RateLimiter limiter,
                            int maxRetries, String modelName) {

    public ModelEndpoint {
        if (model == null || limiter == null || maxRetries < 0
                || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("模型端点配置无效");
        }
    }
}
