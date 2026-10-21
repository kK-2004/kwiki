package com.kwiki.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvalAdminController.class)
@Import(EvalAdminControllerTest.Config.class)
class EvalAdminControllerTest {

    static final EvalRunService runs = mock(EvalRunService.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        EvalAdminController controller() {
            return new EvalAdminController(mock(EvalCorpusService.class), mock(EvalDatasetSyncService.class), runs);
        }
    }

    @Autowired
    MockMvc mvc;

    static EvalRunService.RunStatus run(EvalRunState state) {
        return new EvalRunService.RunStatus("r-1", "run-a", "50q", state, 50, 12, null, null, false, false,
                Instant.parse("2026-10-21T10:00:00Z"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/admin/eval/runs/r-1/cancel").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listCancelResumeDeleteAndReport() throws Exception {
        when(runs.list()).thenReturn(List.of(run(EvalRunState.CANCELLED)));
        when(runs.cancel("r-1")).thenReturn(run(EvalRunState.CANCELLED));
        when(runs.resume(any(), eq("r-1"))).thenReturn(run(EvalRunState.RUNNING));
        when(runs.delete("r-1", true)).thenReturn(new EvalRunService.DeleteResult(false, "langfuse down"));
        when(runs.report("r-1")).thenReturn(Map.of("runName", "run-a", "cases", List.of()));

        mvc.perform(get("/api/v1/admin/eval/runs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].state").value("CANCELLED"))
                .andExpect(jsonPath("$.data[0].completed").value(12));
        mvc.perform(post("/api/v1/admin/eval/runs/r-1/cancel").with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CANCELLED"));
        mvc.perform(post("/api/v1/admin/eval/runs/r-1/resume").with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RUNNING"));
        mvc.perform(delete("/api/v1/admin/eval/runs/r-1").param("langfuse", "true").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.langfuseDeleted").value(false))
                .andExpect(jsonPath("$.data.langfuseError").value("langfuse down"));
        mvc.perform(get("/api/v1/admin/eval/runs/r-1/report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runName").value("run-a"));
        verify(runs).delete("r-1", true);
    }
}
