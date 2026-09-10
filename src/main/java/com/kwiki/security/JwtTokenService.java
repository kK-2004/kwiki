package com.kwiki.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * 签发并校验 kwiki 的 JWT（HS256）。令牌中唯一的业务身份是
 * 主体用户 id；用户名与权限在每次请求时都从 MySQL 重新派生，
 * 绝不信任令牌声明中的内容。
 */
@Component
public class JwtTokenService {

    private final SecretKey key;
    private final Duration ttl;
    private final Duration renewalWindow;
    private final Clock clock;

    @Autowired
    public JwtTokenService(SecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtTokenService(SecurityProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = properties.tokenTtl();
        this.renewalWindow = properties.tokenRenewalWindow();
        this.clock = clock;
    }

    public String issue(CurrentUser user) {
        return issueAt(user.id(), Instant.now(clock));
    }

    public String issueAt(long userId, Instant issuedAt) {
        Date now = Date.from(issuedAt);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * @throws JwtException 当令牌格式错误、被篡改或已过期时
     */
    public TokenIdentity parse(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .setClock(() -> Date.from(Instant.now(clock)))
                .build()
                .parseClaimsJws(token)
                .getBody();
        if (claims.keySet().stream().anyMatch(name -> !Set.of("sub", "iat", "exp").contains(name))) {
            throw new JwtException("token contains unsupported claims");
        }
        String subject = claims.getSubject();
        if (subject != null && subject.matches("[0-9]+") && claims.getIssuedAt() != null && claims.getExpiration() != null) {
            return new TokenIdentity(
                    Long.parseLong(subject),
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant());
        }
        throw new JwtException("token is missing required claims");
    }

    public String renewIfEligible(TokenIdentity token) {
        Instant now = Instant.now(clock);
        if (now.isBefore(token.issuedAt())
                || !now.isBefore(token.expiresAt())
                || now.isAfter(token.issuedAt().plus(renewalWindow))) {
            return null;
        }
        return issueAt(token.userId(), now);
    }

    public Duration ttl() {
        return ttl;
    }

    public record TokenIdentity(long userId, Instant issuedAt, Instant expiresAt) {
    }
}
