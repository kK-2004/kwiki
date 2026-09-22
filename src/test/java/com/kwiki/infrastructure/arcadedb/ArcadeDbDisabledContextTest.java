package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.config.ArcadeDbProperties;
import com.kwiki.graph.config.GraphConfigurationGuard;
import com.kwiki.graph.config.GraphProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ArcadeDbDisabledContextTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DisabledConfiguration.class)
            .withPropertyValues(
                    "kwiki.graph.enabled=false",
                    "kwiki.external.arcadedb.endpoint=",
                    "kwiki.external.arcadedb.database=",
                    "kwiki.external.arcadedb.token=");

    @Test
    void disabledGraphDoesNotCreateAdapterOrCapabilityProbe() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(ArcadeDbHttpAdapter.class);
            assertThat(application).doesNotHaveBean(ArcadeDbCapabilityProbe.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({GraphProperties.class, ArcadeDbProperties.class})
    @org.springframework.context.annotation.Import({
            GraphConfigurationGuard.class,
            ArcadeDbConfiguration.class,
            ArcadeDbCapabilityConfiguration.class})
    static class DisabledConfiguration {
    }
}
