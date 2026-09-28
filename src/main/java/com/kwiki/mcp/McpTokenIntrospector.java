package com.kwiki.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 进程内受信内省：把 Bearer 访问令牌直接查到本机授权服务器持久化的
 * OAuth2Authorization 上。身份事实以授权记录为准——principalName、
 * registeredClientId 与 authorizedScopes——用户当前状态（禁用即拒绝）
 * 与角色在每次请求时从数据库重新派生，绝不信任令牌内容。
 * 授权被撤销（记录删除/失效）后已有访问令牌立即失效。
 */
@Component
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpTokenIntrospector implements OpaqueTokenIntrospector {

    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final com.kwiki.wiki.persistence.AppUserRepository users;

    public McpTokenIntrospector(OAuth2AuthorizationService authorizations,
                                RegisteredClientRepository clients,
                                com.kwiki.wiki.persistence.AppUserRepository users) {
        this.authorizations = authorizations;
        this.clients = clients;
        this.users = users;
    }

    @Override
    public McpAuthenticatedPrincipal introspect(String token) {
        OAuth2Authorization authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null) {
            throw new BadOpaqueTokenException("unknown access token");
        }
        OAuth2Authorization.Token<org.springframework.security.oauth2.core.OAuth2AccessToken> accessToken =
                authorization.getToken(org.springframework.security.oauth2.core.OAuth2AccessToken.class);
        if (accessToken == null || !accessToken.isActive()) {
            throw new BadOpaqueTokenException("access token is expired or revoked");
        }
        var registeredClient = clients.findById(authorization.getRegisteredClientId());
        if (registeredClient == null) {
            throw new OAuth2IntrospectionException("registered client vanished");
        }
        com.kwiki.wiki.domain.AppUser user = users.findByUsername(authorization.getPrincipalName())
                .filter(com.kwiki.wiki.domain.AppUser::isActive)
                .orElseThrow(() -> new OAuth2IntrospectionException("token principal is unavailable"));
        McpActor actor = new McpActor(
                user.getId(),
                user.getUsername(),
                user.isAdmin(),
                registeredClient.getClientId(),
                authorization.getAuthorizedScopes());
        Map<String, Object> attributes = new HashMap<>();
        Map<String, Object> claims = accessToken.getClaims();
        if (claims != null) {
            attributes.putAll(claims);
        }
        attributes.put("preferred_username", user.getUsername());
        attributes.put("client_id", registeredClient.getClientId());
        attributes.put("uid", user.getId());
        return new McpAuthenticatedPrincipal(actor, attributes);
    }
}
