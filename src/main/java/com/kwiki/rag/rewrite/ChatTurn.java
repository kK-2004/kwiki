package com.kwiki.rag.rewrite;

/** 作为有界改写上下文使用的一轮历史对话。 */
public record ChatTurn(String role, String content) {

    public boolean isUser() {
        return "USER".equalsIgnoreCase(role);
    }
}
