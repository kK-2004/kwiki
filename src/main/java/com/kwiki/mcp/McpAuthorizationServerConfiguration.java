package com.kwiki.mcp;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;

import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * MCP 授权服务器：本应用唯一的受保护资源是 /mcp，因此授权服务器
 * 内建于同进程，由 Spring Authorization Server 承载授权码+PKCE、
 * 刷新轮换与撤销的完整生命周期。访问令牌为随机不透明值，校验交给
 * 资源链的进程内内省器；令牌值按 SAS 标准持久化（不做应用层哈希，
 * 见 mcpAuthorizationService 注释）。
 *
 * <p>授权页同意步骤对首方只读 scope（mcp:search）自动通过：用户在
 * /login 以既有账号密码完成身份认证即完成授权；动态客户端注册只签发
 * 不带密钥的公共客户端并强制 PKCE。会话仅存在于授权服务器端点链，
 * /api 与 /mcp 均无会话或 Basic 等意外回退。</p>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpAuthorizationServerConfiguration {

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(McpProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.issuer())
                .build();
    }

    @Bean
    public OAuth2TokenGenerator<?> kwikiTokenGenerator(McpProperties properties) {
        return new DelegatingOAuth2TokenGenerator(
                new KwikiOpaqueAccessTokenGenerator(),
                new KwikiRefreshTokenGenerator());
    }

    @Bean
    public RegisteredClientRepository mcpRegisteredClientRepository(
            McpProperties properties, ObjectProvider<JdbcOperations> jdbc) {
        if (properties.jdbcStore()) {
            return new JdbcRegisteredClientRepository(requireJdbc(jdbc));
        }
        // 内存仓库不接受空集合：放置一个永不参与授权的占位客户端，
        // 真实客户端经动态注册或直接 save 写入。
        return new InMemoryRegisteredClientRepository(placeholderClient());
    }

    private static org.springframework.security.oauth2.server.authorization.client.RegisteredClient placeholderClient() {
        return org.springframework.security.oauth2.server.authorization.client.RegisteredClient
                .withId("kwiki-mcp-placeholder")
                .clientId("kwiki-mcp-placeholder")
                .clientName("placeholder (never used for authorization)")
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1/invalid-placeholder")
                .scope(McpActor.SEARCH_SCOPE)
                .clientSettings(org.springframework.security.oauth2.server.authorization.settings.ClientSettings
                        .builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .build();
    }

    @Bean
    public OAuth2AuthorizationService mcpAuthorizationService(
            McpProperties properties,
            RegisteredClientRepository clients,
            ObjectProvider<JdbcOperations> jdbc) {
        // 令牌值按 SAS 标准存入 oauth2_authorization（不做应用层哈希）。
        // SAS 的刷新轮换/撤销契约依赖「呈上值 == 存储值」的等值比较，
        // 应用层哈希会静默破坏该契约；缓解手段是随机不透明值、
        // 每次刷新强制轮换与较短的 TTL。
        return properties.jdbcStore()
                ? new JdbcOAuth2AuthorizationService(requireJdbc(jdbc), clients)
                : new InMemoryOAuth2AuthorizationService();
    }

    private static JdbcOperations requireJdbc(ObjectProvider<JdbcOperations> jdbc) {
        JdbcOperations operations = jdbc.getIfAvailable();
        if (operations == null) {
            throw new IllegalStateException(
                    "kwiki.mcp.auth-store=jdbc requires a DataSource; set kwiki.mcp.auth-store=memory for standalone/dev runs");
        }
        return operations;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain mcpAuthorizationServerSecurityFilterChain(
            HttpSecurity http,
            OAuth2AuthorizationService authorizationService,
            OAuth2TokenGenerator<?> tokenGenerator,
            McpProperties properties) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();
        http
                .securityMatcher(new OrRequestMatcher(
                        authorizationServerConfigurer.getEndpointsMatcher(),
                        new AntPathRequestMatcher("/login"),
                        new AntPathRequestMatcher("/connect/register")))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        new AntPathRequestMatcher("/connect/register", "POST")))
                .formLogin(form -> form.loginPage("/login"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/connect/register").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        // 浏览器访问授权端点时重定向到登录页；非浏览器客户端得到 401。
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer
                        .authorizationService(authorizationService)
                        .tokenGenerator(tokenGenerator)
                        .authorizationServerMetadataEndpoint(metadata -> metadata
                                .authorizationServerMetadataCustomizer(customizer -> customizer
                                        .clientRegistrationEndpoint(properties.requireBaseUrl()
                                                + "/connect/register"))));
        return http.build();
    }
}
