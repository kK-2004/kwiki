package com.kwiki.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面向 MCP 客户端的动态客户端注册（RFC 7591 子集）：只签发不带密钥的
 * 公共客户端，强制 PKCE 与授权码+刷新令牌授予，scope 固定为
 * mcp:search。redirect URI 必须逐条校验：HTTPS（生产）或环回地址
 * （开发），拒绝任意主机名，防止把授权码投递给第三方端点。
 * 注册不落任何凭据；客户端密钥请求一律拒绝。
 */
@RestController
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpClientRegistrationController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED_GRANT_TYPES =
            Set.of("authorization_code", "refresh_token");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final int MAX_REDIRECT_URIS = 5;
    private static final int MAX_CLIENT_NAME = 200;

    private final RegisteredClientRepository clients;
    private final McpProperties properties;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public McpClientRegistrationController(RegisteredClientRepository clients,
                                           McpProperties properties,
                                           org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.clients = clients;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    public record RegistrationRequest(
            List<String> redirect_uris,
            String client_name,
            String scope,
            List<String> grant_types,
            List<String> response_types,
            String token_endpoint_auth_method) {
    }

    @PostMapping(
            value = "/connect/register",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest request) {
        if (!properties.dynamicClientRegistration()) {
            return error("access_denied", "dynamic client registration is disabled",
                    HttpStatus.FORBIDDEN);
        }
        if (request == null || request.redirect_uris() == null || request.redirect_uris().isEmpty()) {
            return error("invalid_redirect_uri", "redirect_uris is required", HttpStatus.BAD_REQUEST);
        }
        if (request.redirect_uris().size() > MAX_REDIRECT_URIS) {
            return error("invalid_redirect_uri",
                    "at most " + MAX_REDIRECT_URIS + " redirect URIs", HttpStatus.BAD_REQUEST);
        }
        List<String> redirectUris = new ArrayList<>();
        for (String candidate : request.redirect_uris()) {
            String violation = validateRedirectUri(candidate);
            if (violation != null) {
                return error("invalid_redirect_uri", violation, HttpStatus.BAD_REQUEST);
            }
            redirectUris.add(candidate.trim());
        }
        if (request.scope() != null && !McpActor.SEARCH_SCOPE.equals(request.scope().trim())) {
            return error("invalid_client_metadata", "only scope " + McpActor.SEARCH_SCOPE
                    + " is available", HttpStatus.BAD_REQUEST);
        }
        if (request.grant_types() != null && !ALLOWED_GRANT_TYPES.containsAll(request.grant_types())) {
            return error("invalid_client_metadata",
                    "only authorization_code and refresh_token grants are available",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.token_endpoint_auth_method() != null
                && !"none".equals(request.token_endpoint_auth_method())) {
            return error("invalid_client_metadata",
                    "only public clients (token_endpoint_auth_method=none) are supported",
                    HttpStatus.BAD_REQUEST);
        }
        String clientName = request.client_name() == null || request.client_name().isBlank()
                ? "MCP client"
                : request.client_name().trim();
        if (clientName.length() > MAX_CLIENT_NAME) {
            clientName = clientName.substring(0, MAX_CLIENT_NAME);
        }

        Instant issuedAt = Instant.now();
        String clientSecret = newClientSecret();
        RegisteredClient client = RegisteredClient.withId(java.util.UUID.randomUUID().toString())
                .clientId(newClientId())
                .clientIdIssuedAt(issuedAt)
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientName(clientName)
                // 机密客户端：刷新与撤销走标准 client_secret 认证；
                // PKCE 可选但客户端仍被鼓励使用。
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(redirectUris))
                .scope(McpActor.SEARCH_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(false)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .refreshTokenTimeToLive(properties.refreshTokenTtl())
                        .reuseRefreshTokens(false)
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
        clients.save(client);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "client_id", client.getClientId(),
                "client_secret", clientSecret,
                "client_id_issued_at", issuedAt.getEpochSecond(),
                "client_name", clientName,
                "redirect_uris", redirectUris,
                "token_endpoint_auth_method", "client_secret_basic",
                "grant_types", List.of("authorization_code", "refresh_token"),
                "response_types", List.of("code"),
                "scope", McpActor.SEARCH_SCOPE));
    }

    private String validateRedirectUri(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "redirect URI must not be blank";
        }
        URI uri;
        try {
            uri = URI.create(candidate.trim());
        } catch (IllegalArgumentException e) {
            return "redirect URI is not a valid URI";
        }
        if (uri.getFragment() != null) {
            return "redirect URI must not contain a fragment";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = LOOPBACK_HOSTS.contains(host);
        if (scheme.equals("https")) {
            return null;
        }
        if (scheme.equals("http") && loopback) {
            return null;
        }
        return "redirect URI must be https, or http on a loopback address: " + candidate;
    }

    private static String newClientId() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return "kwikimcp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String newClientSecret() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static ResponseEntity<Map<String, Object>> error(String code, String description,
                                                             HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of(
                "error", code,
                "error_description", description));
    }
}
