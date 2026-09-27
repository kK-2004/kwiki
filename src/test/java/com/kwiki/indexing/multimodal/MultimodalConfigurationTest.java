package com.kwiki.indexing.multimodal;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultimodalConfigurationTest {
    @Test
    void missingVisionCredentialsKeepApplicationBeanAvailableButFailClosedOnUse() {
        ExternalServicesProperties properties = new ExternalServicesProperties(
                null, null, null, null, ExternalServicesProperties.unusedVisionModel());
        ImageSummaryPort port = new MultimodalConfiguration().qwenVisionSummaryClient(
                WebClient.builder(), properties, null, null);

        assertThatThrownBy(() -> port.summarize("https://cdn.example/image.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vision model configuration is unavailable");
    }
}
