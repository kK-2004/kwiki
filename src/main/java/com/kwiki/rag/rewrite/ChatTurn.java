package com.kwiki.rag.rewrite;

/** One prior chat turn used as bounded rewrite context. */
public record ChatTurn(String role, String content) {

    public boolean isUser() {
        return "USER".equalsIgnoreCase(role);
    }
}
