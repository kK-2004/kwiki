package com.kwiki.mcp;

import com.kwiki.security.CurrentUser;

import java.util.Set;

/**
 * 从已验证的 Bearer 访问令牌派生的不可变 MCP 调用方身份。唯一可信来源是
 * 资源服务器链完成令牌内省后的认证结果；工具参数里的任何字段都不得
 * 参与身份构建。
 *
 * <p>该身份在 HTTP 边界（transport 的 contextExtractor，运行于已通过
 * Spring Security 过滤器链的请求线程）被固化进 MCP transport 上下文，
 * 再由工具处理器取出——不依赖 ThreadLocal 是否与工具执行线程一致。</p>
 */
public record McpActor(
        long userId,
        String username,
        boolean admin,
        String clientId,
        Set<String> scopes) {

    /** MCP transport 上下文中携带 {@link McpActor} 的键。 */
    public static final String TRANSPORT_CONTEXT_KEY = "kwiki.mcp.actor";

    /** MCP 入口 scope：token 必须包含它，/mcp 才放行。 */
    public static final String SEARCH_SCOPE = "mcp:search";

    public McpActor {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public CurrentUser currentUser() {
        return new CurrentUser(userId, username, admin);
    }

    public boolean hasSearchScope() {
        return scopes.contains(SEARCH_SCOPE);
    }
}
