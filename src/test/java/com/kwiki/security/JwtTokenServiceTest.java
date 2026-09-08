package com.kwiki.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void tokenContainsOnlyUserIdAndStandardTimeClaims() {
        JwtTokenService service = serviceAt(T0);
        String token = service.issue(new CurrentUser(42L, "ignored", true));
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .setClock(() -> java.util.Date.from(T0))
                .build().parseClaimsJws(token).getBody();

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("uid")).isNull();
        assertThat(claims.get("adm")).isNull();
        assertThat(claims.get("username")).isNull();
        assertThat(service.parse(token).userId()).isEqualTo(42L);
    }

    @Test
    void renewsThroughFiveDayBoundaryButNotAfterIt() {
        JwtTokenService service = serviceAt(T0);
        JwtTokenService.TokenIdentity identity = service.parse(service.issueAt(42L, T0));
        assertThat(service.renewIfEligible(identity)).isNotBlank();

        JwtTokenService atFiveDays = serviceAt(T0.plus(Duration.ofDays(5)));
        assertThat(atFiveDays.renewIfEligible(identity)).isNotBlank();

        JwtTokenService afterWindow = serviceAt(T0.plus(Duration.ofDays(5)).plusSeconds(1));
        assertThat(afterWindow.renewIfEligible(identity)).isNull();

        JwtTokenService atExpiry = serviceAt(T0.plus(Duration.ofDays(7)));
        assertThat(atExpiry.renewIfEligible(identity)).isNull();
    }

    @Test
    void rejectsLegacyPermissionClaims() {
        JwtTokenService service = serviceAt(T0);
        String legacy = Jwts.builder().setSubject("alice").claim("uid", 42L).claim("adm", true)
                .setIssuedAt(java.util.Date.from(T0)).setExpiration(java.util.Date.from(T0.plus(Duration.ofDays(7))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertThatThrownBy(() -> service.parse(legacy)).isInstanceOf(RuntimeException.class);
    }

    private JwtTokenService serviceAt(Instant instant) {
        return new JwtTokenService(new SecurityProperties(SECRET, Duration.ofDays(7), Duration.ofDays(5)),
                Clock.fixed(instant, ZoneOffset.UTC));
    }
}
