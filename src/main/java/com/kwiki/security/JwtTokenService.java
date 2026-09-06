package com.kwiki.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * Issues and validates kwiki JWTs (HS256). Tokens carry the user id, username and
 * admin flag; roles are re-derived on every request, never trusted from claims.
 */
@Component
public class JwtTokenService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtTokenService(SecurityProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = properties.tokenTtl();
    }

    public String issue(CurrentUser user) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(user.username())
                .claim("uid", user.id())
                .claim("adm", user.admin())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * @throws JwtException when the token is malformed, tampered with, or expired
     */
    public CurrentUser parse(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
        Object uid = claims.get("uid");
        if (uid instanceof Number number && claims.getSubject() != null) {
            boolean admin = Boolean.TRUE.equals(claims.get("adm"));
            return new CurrentUser(number.longValue(), claims.getSubject(), admin);
        }
        throw new JwtException("token is missing required claims");
    }
}
