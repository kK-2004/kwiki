package com.kwiki.infrastructure;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the readiness/liveness split: a simulated dependency outage (an indicator
 * reporting OUT_OF_SERVICE) must move readiness to OUT_OF_SERVICE while liveness
 * stays UP, so a flaky external service never restarts the process.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(com.kwiki.testutil.WikiMockBeans.class)
@AutoConfigureMockMvc
class DependencyReadinessIntegrationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void registerStandardTestProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
    }

    @TestConfiguration
    static class SimulatedDependencyOutage {

        @Bean
        HealthIndicator kwikiSimulatedOutage() {
            return () -> Health.outOfService().withDetail("provider", "simulated").build();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void livenessStaysUpDuringDependencyOutage() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessReportsOutOfServiceDuringDependencyOutage() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    }
}
