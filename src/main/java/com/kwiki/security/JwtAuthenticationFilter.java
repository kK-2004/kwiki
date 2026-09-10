package com.kwiki.security;

import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 Bearer JWT 解析为 CurrentUser 主体。无效或缺失的令牌会让上下文
 * 保持为空，因此受保护端点会返回已净化的 401。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService tokens;
    private final com.kwiki.wiki.persistence.AppUserRepository users;

    public JwtAuthenticationFilter(JwtTokenService tokens,
                                   com.kwiki.wiki.persistence.AppUserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    /** MVC 在 ASYNC 分派时恢复 SSE；此处也需恢复 JWT 认证。 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtTokenService.TokenIdentity identity = tokens.parse(header.substring(7).trim());
                CurrentUser user = users.findById(identity.userId())
                        .filter(com.kwiki.wiki.domain.AppUser::isActive)
                        .map(row -> new CurrentUser(row.getId(), row.getUsername(), row.isAdmin()))
                        .orElseThrow(() -> new JwtException("user is unavailable"));
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                if (user.admin()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
                var authentication =
                        new UsernamePasswordAuthenticationToken(user, null, authorities);
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                if (!"true".equalsIgnoreCase(request.getHeader("X-Kwiki-Maintenance"))) {
                    String renewed = tokens.renewIfEligible(identity);
                    if (renewed != null) {
                        response.setHeader("X-Auth-Token", renewed);
                        response.setHeader("Access-Control-Expose-Headers", "X-Auth-Token");
                    }
                }
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
