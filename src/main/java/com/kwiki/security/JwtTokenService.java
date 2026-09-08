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
 * Issues and validates kwiki JWTs (HS256). The only business identity in a token is
 * the subject user id; username and permissions are re-derived from MySQL on every
 * request and are never trusted from claims.
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
     * @throws JwtException when the token is malformed, tampered with, or expired
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
