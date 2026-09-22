package com.kwiki.wiki.api;

import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.persistence.GraphAdminCommandService;
import com.kwiki.graph.persistence.GraphAdminQueryService;
import com.kwiki.graph.persistence.GraphBuildBatchService;
import com.kwiki.graph.persistence.GraphBuildRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 13.6 图管理 API 越权拒绝与幂等命令验证。 */
@WebMvcTest
class GraphBuildAdminControllerSecurityTest {

    @Configuration @EnableMethodSecurity
    static class MethodSecurity {
        @Bean
        GraphBuildAdminController controller(GraphBuildBatchService batches,
                GraphAdminQueryService queries, GraphAdminCommandService commands,
                GraphBuildRepository repository) {
            return new GraphBuildAdminController(batches, queries, commands, repository,
                    new GraphProperties(false,
                            com.kwiki.graph.GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                            "0 0 2 * * *", "Asia/Shanghai", false,
                            java.time.Duration.ofMinutes(10), 2,
                            java.time.Duration.ofMinutes(30),
                            GraphProperties.Capacity.defaults()));
        }
    }

    @Autowired MockMvc mvc;
    @MockBean GraphBuildBatchService batches;
    @MockBean GraphAdminQueryService queries;
    @MockBean GraphAdminCommandService commands;
    @MockBean GraphBuildRepository repository;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge-graphs/batches"))
                .andExpect(status().isUnauthorized());
    }

    @Test @WithMockUser(roles = "USER")
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge-graphs/batches"))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = "USER")
    void nonAdminWriteIsForbiddenWithoutReachingCommandService() throws Exception {
        mvc.perform(post("/api/v1/admin/knowledge-graphs/runs/1/retry")
                        .header("Idempotency-Key", "k1").with(csrf()))
                .andExpect(status().isForbidden());
        verify(commands, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test @WithMockUser(roles = "ADMIN")
    void adminCanReadStatusProjectionWithoutCredentials() throws Exception {
        when(queries.batches()).thenReturn(java.util.List.of());
        when(repository.findActiveBatchId()).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/admin/knowledge-graphs/batches"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/knowledge-graphs/service-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.scheduleCron").value("0 0 2 * * *"));
    }

    @Test
    void adminWriteCommandRequiresIdempotencyKeyAndReplaysIdempotently() throws Exception {
        org.springframework.security.authentication.TestingAuthenticationToken admin =
                new org.springframework.security.authentication.TestingAuthenticationToken(
                        new com.kwiki.security.CurrentUser(1L, "admin", true), null,
                        "ROLE_ADMIN");
        when(commands.execute(eq("key-1"), eq("RUN_RETRY"), eq("admin"), any(), any(),
                any())).thenReturn(Map.of("runId", 1, "requeued", true));

        mvc.perform(post("/api/v1/admin/knowledge-graphs/runs/1/retry")
                        .header("Idempotency-Key", "key-1").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.authentication(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requeued").value(true));

        // 相同幂等键重复投递：由命令服务重放，不产生额外副作用。
        mvc.perform(post("/api/v1/admin/knowledge-graphs/runs/1/retry")
                        .header("Idempotency-Key", "key-1").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.authentication(admin)))
                .andExpect(status().isOk());

        // 缺少幂等键的写命令被拒绝。
        mvc.perform(post("/api/v1/admin/knowledge-graphs/runs/1/cancel")
                        .with(csrf())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.authentication(admin)))
                .andExpect(status().isBadRequest());
    }
}
