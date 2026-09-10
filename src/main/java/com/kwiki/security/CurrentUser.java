package com.kwiki.security;

/**
 * 从已校验的 JWT 解析出的已认证调用方。作为整个请求期间
 * 的 Spring Security 主体（principal）传递的不可变值。
 */
public record CurrentUser(Long id, String username, boolean admin) {

    public boolean isAdmin() {
        return admin;
    }
}
