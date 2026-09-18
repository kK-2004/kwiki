package com.kwiki.indexing.version;

import com.kwiki.wiki.api.SearchIndexAdminController;
import com.kwiki.wiki.api.SearchIndexAdminExceptionAdvice;

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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchIndexAdminController.class)
@Import({SearchIndexAdminControllerSecurityTest.MethodSecurity.class,
        SearchIndexAdminExceptionAdvice.class})
class SearchIndexAdminControllerSecurityTest {
    @Configuration @EnableMethodSecurity static class MethodSecurity {
        @org.springframework.context.annotation.Bean
        SearchIndexAdminController controller(SearchIndexAdminQueryService queries,
                SearchIndexAdminService admin,ManualIndexRebuildService rebuilds,
                RebuildRunControlService controls,SwitchPreparationService preparations,
                AliasSwitchService switches,SearchIndexValidationService validations,
                IndexVersionEnablementService enablement,SearchIndexDeletionService deletion,
                AdminCommandIdempotency commands,SearchIndexObservability observability){
            return new SearchIndexAdminController(queries,admin,rebuilds,controls,preparations,
                    switches,validations,enablement,deletion,commands,observability);
        }
    }

    @Autowired MockMvc mvc;
    @MockBean SearchIndexVersionRepository versionRepository;
    @MockBean SearchIndexAdminQueryService queries;
    @MockBean SearchIndexAdminService admin;
    @MockBean ManualIndexRebuildService rebuilds;
    @MockBean RebuildRunControlService controls;
    @MockBean SwitchPreparationService preparations;
    @MockBean AliasSwitchService switches;
    @MockBean SearchIndexValidationService validations;
    @MockBean IndexVersionEnablementService enablement;
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
}
