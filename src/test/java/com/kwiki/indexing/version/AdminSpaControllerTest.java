package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSpaController.class)
@org.springframework.context.annotation.Import({com.kwiki.security.SecurityConfiguration.class,
        com.kwiki.security.RestAuthenticationEntryPoint.class,
        com.kwiki.security.RestAccessDeniedHandler.class})
class AdminSpaControllerTest {
    @Autowired MockMvc mvc;
    @org.springframework.boot.test.mock.mockito.MockBean io.micrometer.tracing.Tracer tracer;
    @org.springframework.boot.test.mock.mockito.MockBean com.kwiki.security.JwtTokenService tokens;
    @org.springframework.boot.test.mock.mockito.MockBean com.kwiki.wiki.persistence.AppUserRepository users;
    @org.springframework.boot.test.mock.mockito.MockBean com.kwiki.security.DatabaseUserDetailsService userDetails;
    @Test void nestedAdminRouteLoadsSpaEntryAnonymously()throws Exception{
        mvc.perform(get("/admin/search-indexes/runs/123")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/index.html"));
    }
    @Test void missingAssetIsNotRewrittenToHtml()throws Exception{
        mvc.perform(get("/admin/assets/missing.js")).andExpect(status().isNotFound());
    }
    @Test void apiAndActuatorAreOutsideTheFallbackMapping()throws Exception{
        mvc.perform(get("/api/v1/admin/search-indexes/versions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/not-an-admin-route")).andExpect(status().isUnauthorized());
    }
}
