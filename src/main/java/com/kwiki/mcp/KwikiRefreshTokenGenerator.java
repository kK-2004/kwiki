package com.kwiki.mcp;

import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Instant;

/**
 * 刷新令牌生成器。Spring Authorization Server 内置的
 * OAuth2RefreshTokenGenerator 会拒绝为「公共客户端 + 授权码」签发
 * 刷新令牌；MCP 客户端依赖刷新令牌在访问令牌过期后静默续期，
 * 因此本类在相同安全约束下放行该组合：仍强制 PKCE 与轮换
 * （reuseRefreshTokens=false 时，旧刷新令牌重放会由框架撤销整个授权）。
 */
public class KwikiRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private static final StringKeyGenerator KEY_GENERATOR = KeyGenerators.string();

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (context == null
                || !org.springframework.security.oauth2.server.authorization.OAuth2TokenType.REFRESH_TOKEN
                        .equals(context.getTokenType())) {
            return null;
        }
        var client = context.getRegisteredClient();
        if (client == null
                || !client.getAuthorizationGrantTypes()
                        .contains(org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)) {
            return null;
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(client.getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(KEY_GENERATOR.generateKey(), issuedAt, expiresAt);
    }
}
