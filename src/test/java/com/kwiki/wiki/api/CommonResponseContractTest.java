package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.security.JwtTokenService;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.testutil.WikiMockBeans;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 共享 kk-common 信封的黄金 JSON 契约（有意引入的破坏性
 * 变更，特此记录）：成功响应为 {code:200, success:true, message:"OK",
 * data:<payload>}；业务失败仍留在该信封内，并携带 SDK 响应体的错误码
 * （共享处理器错误时 HTTP 仍为 200）；Spring Security 过滤器层面的 401/403
 * 响应体保持各自已净化的契约，绝不会被包装。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(WikiMockBeans.class)
class CommonResponseContractTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);
    private static final CurrentUser OWNER = new CurrentUser(7L, "owner", false);
    private static final CurrentUser VIEWER = new CurrentUser(9L, "viewer", false);

    @DynamicPropertySource
    static void registerStandardTestProperties(DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    KnowledgeBaseRepository knowledgeBases;

    @Autowired
    KnowledgeBaseMemberRepository members;

    @Autowired
    WikiPageRepository pages;

    @Autowired
    ScopeVersionService scopeVersions;

    private String auth(CurrentUser user) {
        return "Bearer " + tokens.issue(user);
    }

    private KnowledgeBase kb(long id) {
        KnowledgeBase kb = new KnowledgeBase("uuid-" + id, "kb-" + id, null, OWNER.id());
        ReflectionTestUtils.setField(kb, "id", id);
        ReflectionTestUtils.setField(kb, "status", KnowledgeBase.STATUS_ACTIVE);
        return kb;
    }

    @Test
    void successIsWrappedInTheSharedTransDTOEnvelope() throws Exception {
        when(members.findByUserId(OWNER.id())).thenReturn(List.of(
                new KnowledgeBaseMember(21L, OWNER.id(),
                        com.kwiki.wiki.access.KnowledgeBaseRole.OWNER, OWNER.id())));
        when(knowledgeBases.findByIdInAndStatusOrderByNameAsc(anyList(),
                org.mockito.ArgumentMatchers.eq(KnowledgeBase.STATUS_ACTIVE)))
                .thenReturn(List.of(kb(21L)));

        mockMvc.perform(get("/api/v1/knowledge-bases")
                        .header("Authorization", auth(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].uuid").value("uuid-21"));
    }

    @Test
    void notFoundKeepsItsSemanticsInEnvelopeBodyCode() throws Exception {
        when(knowledgeBases.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/knowledge-bases/99")
                        .header("Authorization", auth(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("knowledge base not found"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void optimisticConflictStaysDistinguishableWithItsBodyCode() throws Exception {
        WikiPage page = new WikiPage("uuid-7", 31L, null, "page 7",
                WikiPage.TYPE_PAGE, 0, ADMIN.id());
        ReflectionTestUtils.setField(page, "id", 7L);
        ReflectionTestUtils.setField(page, "lockVersion", 3L);
        when(pages.findByIdAndStatus(7L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(page));

        mockMvc.perform(put("/api/v1/knowledge-bases/{kbId}/pages/7/draft", 31L)
                        .header("Authorization", auth(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"# overwrite\",\"expectedLockVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("page was modified concurrently"));
    }

    @Test
    void authorizationDeniedIsAnsweredForbiddenByTheAppOwnedHandler() throws Exception {
        long kbId = 32L;
        when(knowledgeBases.findById(kbId)).thenReturn(Optional.of(kb(kbId)));
        when(members.findByKbIdAndUserId(kbId, VIEWER.id()))
                .thenReturn(Optional.of(new KnowledgeBaseMember(kbId, VIEWER.id(),
                        com.kwiki.wiki.access.KnowledgeBaseRole.VIEWER, OWNER.id())));

        mockMvc.perform(put("/api/v1/knowledge-bases/{id}/members", kbId)
                        .header("Authorization", auth(VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":42,\"role\":\"EDITOR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    @Test
    void validationFailuresUseTheSharedHandlerBodyCode() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", auth(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void filterLevelUnauthorizedKeepsItsOwnSanitizedContract() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }
}
