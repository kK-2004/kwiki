package com.kwiki.mcp;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

import java.util.Collection;
import java.util.Map;

/**
 * 令牌内省通过后的主体：以 {@link McpActor} 承载业务身份，
 * 权限仅包含令牌的 SCOPE 与用户角色。contextExtractor 只接受
 * 此类型的主体建立 transport 上下文，其余认证类型一律拒绝。
 */
public final class McpAuthenticatedPrincipal implements OAuth2AuthenticatedPrincipal {

    private final McpActor actor;
    private final Map<String, Object> attributes;
    private final Collection<GrantedAuthority> authorities;

    public McpAuthenticatedPrincipal(McpActor actor, Map<String, Object> attributes) {
        this.actor = actor;
        this.attributes = Map.copyOf(attributes);
        this.authorities = actor.scopes().stream()
                .<GrantedAuthority>map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
    }

    public McpActor actor() {
        return actor;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return actor.username();
    }

    /** 从已建立的认证结果中安全取出 actor；非本服务签发的认证一律为 null。 */
    public static McpActor from(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof McpAuthenticatedPrincipal p) {
            return p.actor();
        }
        return null;
    }
}
