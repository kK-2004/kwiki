package com.kwiki.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import java.nio.charset.StandardCharsets;

/**
 * /mcp 的资源服务器链：每个请求都必须携带 Bearer 访问令牌，经
 * {@link McpTokenIntrospector} 在进程内校验后建立
 * {@link McpAuthenticatedPrincipal}。链路无会话、无 CSRF、无 Basic 等
 * 意外认证回退；未认证或令牌无效返回 401 并携带 resource_metadata 的
 * Bearer challenge（MCP 客户端据此自动发现授权服务器），已认证但
 * scope 不足返回 403 insufficient_scope。发现端点匿名可读。
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpResourceServerConfiguration {

    @Bean
    @Order(2)
    public SecurityFilterChain mcpResourceServerSecurityFilterChain(
            HttpSecurity http,
            McpTokenIntrospector introspector,
            McpProperties properties) throws Exception {
        http
                .securityMatcher(new OrRequestMatcher(
                        new AntPathRequestMatcher("/mcp"),
                        new AntPathRequestMatcher("/mcp/**"),
                        new AntPathRequestMatcher("/.well-known/oauth-protected-resource"),
                        new AntPathRequestMatcher("/.well-known/oauth-protected-resource/*")))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 不引入会话；默认的 RequestAttribute 仓库让 Spring Security
                // 在 Streamable HTTP 的异步分派中恢复已认证上下文。
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/.well-known/oauth-protected-resource",
                                "/.well-known/oauth-protected-resource/*").permitAll()
                        .anyRequest().hasAuthority("SCOPE_" + McpActor.SEARCH_SCOPE))
                .oauth2ResourceServer(oauth -> oauth
                        .opaqueToken(opaque -> opaque.introspector(introspector))
                        .authenticationEntryPoint(new McpBearerEntryPoint(properties))
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()));
        return http.build();
    }

    /**
     * 401 入口点：标准 Bearer 语义（RFC 6750），并追加 MCP 发现所需的
     * resource_metadata 指引。响应体为极简 JSON，不携带任何凭据或内部细节。
     */
    static final class McpBearerEntryPoint implements org.springframework.security.web.AuthenticationEntryPoint {

        private final McpProperties properties;

        McpBearerEntryPoint(McpProperties properties) {
            this.properties = properties;
        }

        @Override
        public void commence(jakarta.servlet.http.HttpServletRequest request,
                             jakarta.servlet.http.HttpServletResponse response,
                             org.springframework.security.core.AuthenticationException authException) {
            BearerTokenError error = authException instanceof BearerTokenAuthenticationException bearer
                    ? bearer.error()
                    : BearerTokenErrors.invalidToken("invalid bearer token");
            String metadata = properties.requireBaseUrl()
                    + "/.well-known/oauth-protected-resource/mcp";
            StringBuilder challenge = new StringBuilder("Bearer");
            if (error.getErrorCode() != null) {
                challenge.append(" error=\"").append(error.getErrorCode()).append("\"");
            }
            if (error.getDescription() != null) {
                challenge.append(", error_description=\"").append(error.getDescription()).append("\"");
            }
            challenge.append(", resource_metadata=\"").append(metadata).append("\"");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge.toString());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            try {
                response.getWriter().write("{\"error\":\"" + error.getErrorCode()
                        + "\",\"message\":\"unauthenticated\"}");
            } catch (java.io.IOException ignored) {
                // 客户端已断开：无需进一步处理。
            }
        }
    }

    /** 携带 Bearer 错误码的认证异常，供入口点还原 error 字段。 */
    static final class BearerTokenAuthenticationException
            extends org.springframework.security.core.AuthenticationException {

        private final BearerTokenError error;

        BearerTokenAuthenticationException(BearerTokenError error) {
            super(error.getErrorCode());
            this.error = error;
        }

        BearerTokenError error() {
            return error;
        }
    }
}
