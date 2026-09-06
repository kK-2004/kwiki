package com.kwiki.infrastructure.observability;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@org.springframework.context.annotation.Import(com.kwiki.testutil.WikiMockBeans.class)
@AutoConfigureMockMvc
// SpringBootTest disables tracing by default, installing a no-op propagation factory.
@AutoConfigureObservability
class TraceIdResponseHeaderFilterTest {

    private static final String W3C_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @org.springframework.test.context.DynamicPropertySource
    static void registerStandardTestProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void incomingW3cTraceparentIsAdoptedAndEchoedAsXTraceId() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")
                        .header("traceparent", W3C_TRACEPARENT))
                .andExpect(status().isOk())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader(TraceIdResponseHeaderFilter.HEADER))
                                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    @Test
    void missingTraceparentGetsGeneratedW3cTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdResponseHeaderFilter.HEADER);
        assertThat(traceId).isNotBlank();
        // Brave is configured for 128-bit ids, so a generated trace id is 32 hex chars
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void invalidTraceparentGetsNewTraceId() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")
                        .header("traceparent", "00-00000000000000000000000000000000-00f067aa0ba902b7-01"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader(TraceIdResponseHeaderFilter.HEADER))
                        .matches("[0-9a-f]{32}")
                        .isNotEqualTo("00000000000000000000000000000000"));
    }

    @Test
    void authenticationFailureStillEchoesIncomingTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/probe/secure")
                        .header("traceparent", W3C_TRACEPARENT))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader(TraceIdResponseHeaderFilter.HEADER))
                        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736"));
    }
}
