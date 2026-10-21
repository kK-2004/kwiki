package com.kwiki.usage;

import com.kwiki.wiki.api.ApiControllerAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端用量查询：仅 ADMIN 可读，匿名与普通用户均拒绝；参数非法返回 400 业务错误。 */
@WebMvcTest(ModelUsageAdminController.class)
@Import({ModelUsageAdminControllerTest.Config.class, ApiControllerAdvice.class})
class ModelUsageAdminControllerTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ModelUsageAdminController controller(ModelUsageQueryService queries) {
            return new ModelUsageAdminController(queries);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean ModelUsageQueryService queries;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/model-usage/users")
                .param("from", "2026-10-20T16:00:00Z").param("to", "2026-10-21T16:00:00Z"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/model-usage/series")
                .param("from", "2026-10-20T16:00:00Z").param("to", "2026-10-21T16:00:00Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void usersAndSeriesAreReturnedInTheSharedEnvelope() throws Exception {
        when(queries.users(Instant.parse("2026-10-20T16:00:00Z"), Instant.parse("2026-10-21T16:00:00Z"),
                Set.of())).thenReturn(List.of(new ModelUsageQueryService.UserView(7, "张三", 900)));
        var series = new ModelUsageQueryService.Series("ANSWER", "回答生成", "dashscope", "qwen-plus",
                new long[]{1}, new long[]{0}, new long[]{100}, new long[]{20}, new long[]{50});
        when(queries.series(Instant.parse("2026-10-20T16:00:00Z"), Instant.parse("2026-10-21T16:00:00Z"),
                Set.of(), Set.of(), "auto", 480))
                .thenReturn(new ModelUsageQueryService.SeriesResponse(300, List.of(1L, 2L), List.of(series)));

        mvc.perform(get("/api/v1/admin/model-usage/users")
                .param("from", "2026-10-20T16:00:00Z").param("to", "2026-10-21T16:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(7))
                .andExpect(jsonPath("$.data[0].displayName").value("张三"))
                .andExpect(jsonPath("$.data[0].totalTokens").value(900));
        mvc.perform(get("/api/v1/admin/model-usage/series")
                .param("from", "2026-10-20T16:00:00Z").param("to", "2026-10-21T16:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bucketSeconds").value(300))
                .andExpect(jsonPath("$.data.buckets[1]").value(2))
                .andExpect(jsonPath("$.data.series[0].step").value("ANSWER"))
                .andExpect(jsonPath("$.data.series[0].stepLabel").value("回答生成"))
                .andExpect(jsonPath("$.data.series[0].provider").value("dashscope"))
                .andExpect(jsonPath("$.data.series[0].model").value("qwen-plus"))
                .andExpect(jsonPath("$.data.series[0].inputTokens[0]").value(100))
                .andExpect(jsonPath("$.data.series[0].latencyMs[0]").value(50));
        verify(queries).series(Instant.parse("2026-10-20T16:00:00Z"), Instant.parse("2026-10-21T16:00:00Z"),
                Set.of(), Set.of(), "auto", 480);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidParametersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/model-usage/users")
                .param("from", "not-a-time").param("to", "2026-10-21T16:00:00Z"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("invalid-time"));
        mvc.perform(get("/api/v1/admin/model-usage/users")
                .param("from", "2026-10-20T16:00:00Z").param("to", "2026-10-21T16:00:00Z")
                .param("sources", "ONLINE,WRONG"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("invalid-source"));
        mvc.perform(get("/api/v1/admin/model-usage/series")
                .param("from", "2026-10-20T16:00:00Z").param("to", "2026-10-21T16:00:00Z")
                .param("users", "abc"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("invalid-user"));
    }
}
