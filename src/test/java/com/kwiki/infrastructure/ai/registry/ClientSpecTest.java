package com.kwiki.infrastructure.ai.registry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSpecTest {

    @Test
    void stepAndProviderSeparateClientsOfTheSameModel() {
        var rewrite = new ClientSpec("https://x/v1", "k", Duration.ofSeconds(5), "m", 0.0, null, null, Map.of(),
                "dashscope", "REWRITE");
        var router = new ClientSpec("https://x/v1", "k", Duration.ofSeconds(5), "m", 0.0, null, null, Map.of(),
                "dashscope", "ROUTER");
        assertThat(rewrite.fingerprint()).isNotEqualTo(router.fingerprint());
    }

    @Test
    void legacyConstructorsDefaultToEmptyStepAndProvider() {
        var legacy = new ClientSpec("https://x/v1", "k", Duration.ofSeconds(5), "m", 0.0, null, null);
        assertThat(legacy.step()).isEmpty();
        assertThat(legacy.providerName()).isEmpty();
    }
}
