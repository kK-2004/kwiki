package com.kwiki.indexing.version;

import com.kwiki.wiki.api.SearchIndexAdminController;
import com.kwiki.wiki.api.SearchIndexAdminExceptionAdvice;
import com.kwiki.wiki.api.ApiControllerAdvice;
import com.kwiki.indexing.plugin.IndexPluginValidationException;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchIndexAdminController.class)
@Import({SearchIndexAdminControllerSecurityTest.MethodSecurity.class,
        SearchIndexAdminExceptionAdvice.class, ApiControllerAdvice.class})
class SearchIndexAdminControllerSecurityTest {
    @Configuration @EnableMethodSecurity static class MethodSecurity {
        @org.springframework.context.annotation.Bean
        SearchIndexAdminController controller(SearchIndexAdminQueryService queries,
                SearchIndexAdminService admin,IndexVersionWriteService writes,
                RebuildRunControlService controls,IndexMigrationService migrations,
                AliasSwitchService switches,SearchIndexValidationService validations,
                SearchIndexDeletionService deletion,
                AdminCommandIdempotency commands,SearchIndexObservability observability){
            return new SearchIndexAdminController(queries,admin,writes,controls,migrations,
                    switches,validations,deletion,commands,observability);
        }
    }

    @Autowired MockMvc mvc;
    @MockBean SearchIndexVersionRepository versionRepository;
    @MockBean SearchIndexAdminQueryService queries;
    @MockBean SearchIndexAdminService admin;
    @MockBean IndexVersionWriteService writes;
    @MockBean RebuildRunControlService controls;
    @MockBean IndexMigrationService migrations;
    @MockBean AliasSwitchService switches;
    @MockBean SearchIndexValidationService validations;
    @MockBean SearchIndexDeletionService deletion;
    @MockBean AdminCommandIdempotency commands;
    @MockBean SearchIndexObservability observability;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/search-indexes/versions"))
                .andExpect(status().isUnauthorized());
    }

    @Test @WithMockUser(roles="USER")
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/search-indexes/versions"))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles="ADMIN")
    void adminCanReadSanitizedVersionProjection() throws Exception {
        when(queries.versions()).thenReturn(List.of());
        mvc.perform(get("/api/v1/admin/search-indexes/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
    private static final String CONFIG_JSON = """
            {"parserVersion":"kwiki-parse-2","chunkerVersion":"kwiki-chunk-1",
             "embeddingProvider":"default","embeddingModel":"model",
             "embeddingDimensions":1024,"indexSchema":"text-only"}
            """;

    @SuppressWarnings("unchecked")
    private void executeCommands() {
        when(commands.execute(any(), any(), any(), any(), any())).thenAnswer(invocation ->
                ((Supplier<Map<String, Object>>) invocation.getArgument(4)).get());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminPrincipal() {
        return authentication(new UsernamePasswordAuthenticationToken(new CurrentUser(1L, "admin", true),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    void 新建未注册字段格式返回可操作的HTTP400消息() throws Exception {
        executeCommands();
        when(admin.createVersion(any())).thenThrow(IndexPluginValidationException.unavailableSchema("text-only"));
        mvc.perform(post("/api/v1/admin/search-indexes/versions").with(adminPrincipal()).with(csrf())
                        .header("Idempotency-Key", "unknown-schema").contentType(MediaType.APPLICATION_JSON).content(CONFIG_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("字段格式 text-only 未注册或不可用"));
    }

    @Test
    void 编辑缺少能力返回HTTP400并标识能力() throws Exception {
        executeCommands();
        when(admin.editVersion(org.mockito.ArgumentMatchers.eq(2), any())).thenThrow(
                IndexPluginValidationException.missingCapability("text-only", "kwiki-parse-2", IndexCapability.RESOURCE_IDS));
        mvc.perform(put("/api/v1/admin/search-indexes/versions/2").with(adminPrincipal()).with(csrf())
                        .header("Idempotency-Key", "missing-capability").contentType(MediaType.APPLICATION_JSON).content(CONFIG_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("字段格式 text-only 不提供解析器 kwiki-parse-2 需要的能力 RESOURCE_IDS"));
    }

    @Test
    void 普通参数错误仍隐藏内部消息() throws Exception {
        executeCommands();
        when(admin.createVersion(any())).thenThrow(new IllegalArgumentException("backend-private-detail"));
        mvc.perform(post("/api/v1/admin/search-indexes/versions").with(adminPrincipal()).with(csrf())
                        .header("Idempotency-Key", "generic-error").contentType(MediaType.APPLICATION_JSON).content(CONFIG_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("invalid_request"));
    }

}
