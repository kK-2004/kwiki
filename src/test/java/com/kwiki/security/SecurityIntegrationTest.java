package com.kwiki.security;

import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.domain.AppUser;
import com.kwiki.wiki.persistence.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证基线：匿名访问 Wiki 会被拒绝并返回已净化的 401，
 * 已认证用户可通行，角色校验以已净化的 403 作答，被篡改的
 * 令牌会被拒绝且不泄漏校验细节。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({SecurityIntegrationTest.ProbeEndpoints.class, com.kwiki.testutil.WikiMockBeans.class})
class SecurityIntegrationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void registerStandardTestProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
    }

    @RestController
    static class ProbeEndpoints {

        @GetMapping("/api/v1/probe/secure")
        @PreAuthorize("isAuthenticated()")
        String secure() {
            return "pong";
        }

        @GetMapping("/api/v1/probe/admin")
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "admin";
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String bearer(CurrentUser user) {
        return "Bearer " + tokens.issue(user);
    }

    @Test
    void anonymousApiAccessIsDeniedWithSanitizedUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/probe/secure"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"))
                .andExpect(content().string(not(containsString("exception"))))
                .andExpect(content().string(not(containsString("JwtException"))));
    }

    @Test
    void validUserTokenAuthenticates() throws Exception {
        mockMvc.perform(get("/api/v1/probe/secure")
                        .header("Authorization", bearer(new CurrentUser(7L, "alice", false))))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void nonAdminGetsSanitizedForbiddenOnAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/probe/admin")
                        .header("Authorization", bearer(new CurrentUser(7L, "alice", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("forbidden"))
                .andExpect(content().string(not(containsString("AccessDenied"))));
    }

    @Test
    void adminPassesAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/probe/admin")
                        .header("Authorization", bearer(new CurrentUser(1L, "root", true))))
                .andExpect(status().isOk())
                .andExpect(content().string("admin"));
    }

    @Test
    void tamperedTokenIsRejectedAsUnauthenticated() throws Exception {
        String token = tokens.issue(new CurrentUser(7L, "alice", false));
        mockMvc.perform(get("/api/v1/probe/secure")
                        .header("Authorization", "Bearer " + token.substring(0, token.length() - 3) + "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }

    @Test
    void databaseUserCanLoginAndReceiveJwt() throws Exception {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("kk");
        when(user.getPasswordHash()).thenReturn(passwordEncoder.encode("admin"));
        when(user.isAdmin()).thenReturn(true);
        when(user.isActive()).thenReturn(true);
        when(users.findByUsername("kk")).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"kk\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.username").value("kk"))
                .andExpect(jsonPath("$.data.user.admin").value(true));
    }

    @Test
    void invalidDatabaseCredentialsAreSanitized() throws Exception {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("kk");
        when(user.getPasswordHash()).thenReturn(passwordEncoder.encode("admin"));
        when(user.isAdmin()).thenReturn(true);
        when(user.isActive()).thenReturn(true);
        when(users.findByUsername("kk")).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"kk\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("invalid_credentials"));
    }

    @Test
    void nonApiPathsAreDenied() throws Exception {
        mockMvc.perform(get("/internal/anything"))
                .andExpect(status().isUnauthorized());
    }
}
