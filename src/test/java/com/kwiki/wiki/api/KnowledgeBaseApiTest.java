package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.security.JwtTokenService;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.KnowledgeBaseRole;
import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role enforcement and scope-version behavior of the knowledge-base API. Membership
 * changes must bump the scope version and invalidate cached scopes; inaccessible
 * knowledge bases must be indistinguishable from missing ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.kwiki.testutil.WikiMockBeans.class)
class KnowledgeBaseApiTest {

    @DynamicPropertySource
    static void registerStandardTestProperties(DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    ScopeVersionService scopeVersions;

    @Autowired
    KnowledgeBaseRepository knowledgeBases;

    @Autowired
    KnowledgeBaseMemberRepository members;

    @MockBean
    ScopeCache scopeCache;

    private static final CurrentUser OWNER = new CurrentUser(7L, "owner", false);
    private static final CurrentUser OUTSIDER = new CurrentUser(8L, "outsider", false);
    private static final CurrentUser VIEWER = new CurrentUser(9L, "viewer", false);

    private KnowledgeBase kb(long id) {
        KnowledgeBase kb = new KnowledgeBase("uuid-" + id, "kb-" + id, null, OWNER.id());
        ReflectionTestUtils.setField(kb, "id", id);
        ReflectionTestUtils.setField(kb, "status", KnowledgeBase.STATUS_ACTIVE);
        return kb;
    }

    @BeforeEach
    void stubCommon() {
        when(knowledgeBases.save(any(KnowledgeBase.class)))
                .thenAnswer(inv -> {
                    KnowledgeBase saved = inv.getArgument(0);
                    if (saved.getId() == null) {
                        ReflectionTestUtils.setField(saved, "id", 1L);
                    }
                    return saved;
                });
        when(members.save(any(KnowledgeBaseMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private String auth(CurrentUser user) {
        return "Bearer " + tokens.issue(user);
    }

    @Test
    void createReturnsViewAndStoresOwnerMembership() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", auth(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Engineering Wiki\",\"description\":\"docs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Engineering Wiki"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        var memberCaptor = org.mockito.ArgumentCaptor.forClass(KnowledgeBaseMember.class);
        verify(members).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(KnowledgeBaseRole.OWNER);
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    void inaccessibleKnowledgeBaseIsReportedAsNotFound() throws Exception {
        when(knowledgeBases.findById(5L)).thenReturn(Optional.of(kb(5L)));
        when(members.findByKbIdAndUserId(5L, 8L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/knowledge-bases/5")
                        .header("Authorization", auth(OUTSIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void memberManagementBumpsScopeVersionAndInvalidatesCache() throws Exception {
        long kbId = 11L;
        when(knowledgeBases.findById(kbId)).thenReturn(Optional.of(kb(kbId)));
        when(members.findByKbIdAndUserId(kbId, OWNER.id()))
                .thenReturn(Optional.of(new KnowledgeBaseMember(kbId, OWNER.id(),
                        KnowledgeBaseRole.OWNER, OWNER.id())));
        when(members.findByKbIdAndUserId(kbId, 42L)).thenReturn(Optional.empty());
        long before = scopeVersions.current(kbId);

        mockMvc.perform(put("/api/v1/knowledge-bases/{id}/members", kbId)
                        .header("Authorization", auth(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":42,\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.role").value("EDITOR"));

        assertThat(scopeVersions.current(kbId)).as("scope version must advance").isGreaterThan(before);
        verify(scopeCache).invalidate(42L);
    }

    @Test
    void viewerCannotManageMembers() throws Exception {
        long kbId = 12L;
        when(knowledgeBases.findById(kbId)).thenReturn(Optional.of(kb(kbId)));
        when(members.findByKbIdAndUserId(kbId, VIEWER.id()))
                .thenReturn(Optional.of(new KnowledgeBaseMember(kbId, VIEWER.id(),
                        KnowledgeBaseRole.VIEWER, OWNER.id())));

        mockMvc.perform(put("/api/v1/knowledge-bases/{id}/members", kbId)
                        .header("Authorization", auth(VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":42,\"role\":\"EDITOR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    @Test
    void listingReturnsOnlyAccessibleKnowledgeBases() throws Exception {
        KnowledgeBase mine = kb(21L);
        when(members.findByUserId(OWNER.id())).thenReturn(List.of(
                new KnowledgeBaseMember(21L, OWNER.id(), KnowledgeBaseRole.OWNER, OWNER.id())));
        when(knowledgeBases.findByIdInAndStatusOrderByNameAsc(
                List.of(21L), KnowledgeBase.STATUS_ACTIVE)).thenReturn(List.of(mine));

        mockMvc.perform(get("/api/v1/knowledge-bases")
                        .header("Authorization", auth(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].uuid").value("uuid-21"));
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isUnauthorized());
    }
}
