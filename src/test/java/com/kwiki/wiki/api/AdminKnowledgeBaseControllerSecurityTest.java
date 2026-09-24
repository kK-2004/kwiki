package com.kwiki.wiki.api;

import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端知识库清单：仅 ADMIN 可读，匿名与普通用户均拒绝。 */
@WebMvcTest
class AdminKnowledgeBaseControllerSecurityTest {

    @Configuration @EnableMethodSecurity
    static class MethodSecurity {
        @Bean
        AdminKnowledgeBaseController controller(KnowledgeBaseRepository knowledgeBases) {
            return new AdminKnowledgeBaseController(knowledgeBases);
        }
    }

    @Autowired MockMvc mvc;
    @MockBean KnowledgeBaseRepository knowledgeBases;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge-bases"))
                .andExpect(status().isUnauthorized());
    }

    @Test @WithMockUser(roles = "USER")
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge-bases"))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = "ADMIN")
    void adminGetsIdAndNameProjectionOnly() throws Exception {
        KnowledgeBase kb = new KnowledgeBase("uuid-1", "研发空间", "内部资料", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(kb, "id", 5L);
        when(knowledgeBases.findByStatusOrderByNameAsc(KnowledgeBase.STATUS_ACTIVE))
                .thenReturn(List.of(kb));
        mvc.perform(get("/api/v1/admin/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(5))
                .andExpect(jsonPath("$.data[0].name").value("研发空间"))
                .andExpect(jsonPath("$.data[0].description").doesNotExist());
    }
}
