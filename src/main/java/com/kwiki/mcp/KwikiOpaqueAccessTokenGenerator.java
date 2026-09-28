package com.kwiki.mcp;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * 不透明访问令牌生成器：48 字节随机值经 base64url 编码作为令牌值，
 * 有效期取发起客户端 TokenSettings 的访问令牌 TTL。授权码、刷新令牌
 * 等其余类型返回 null，交由委托链上的其他生成器处理。
 *
 * <p>令牌的授权事实（用户、客户端、scope、有效期、撤销状态）以
 * 授权服务器持久化的授权记录为准，内省时逐项重新校验；令牌值入库前
 * 经 SHA-256 哈希（见 {@link HashingOAuth2AuthorizationService}），
 * 数据库中不存在可直接重放的 Bearer 凭据。</p>
 */
public class KwikiOpaqueAccessTokenGenerator implements OAuth2TokenGenerator<OAuth2AccessToken> {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public OAuth2AccessToken generate(OAuth2TokenContext context) {
        if (context == null
                || !org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN
                        .equals(context.getTokenType())) {
            return null;
        }
        Authentication principalAuthentication = context.getPrincipal();
        if (principalAuthentication == null || !(principalAuthentication.getPrincipal()
                instanceof com.kwiki.security.DatabaseUserDetails userDetails)) {
            throw new IllegalStateException("access token requires a kwiki user principal");
        }
        Objects.requireNonNull(userDetails.currentUser().id(), "user id");
        var client = Objects.requireNonNull(context.getRegisteredClient(), "registered client");
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(
                client.getTokenSettings().getAccessTokenTimeToLive());
        byte[] raw = new byte[48];
        RANDOM.nextBytes(raw);
        String tokenValue = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                tokenValue,
                issuedAt,
                expiresAt,
                context.getAuthorizedScopes());
    }
}
