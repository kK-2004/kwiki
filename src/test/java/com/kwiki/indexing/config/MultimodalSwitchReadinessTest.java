package com.kwiki.indexing.config;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultimodalSwitchReadinessTest {
    @Test
    void eachMissingPrerequisiteBlocksV2WithoutExposingCredentialValues() {
        assertThat(readiness(false, "https://vision.example/v1", "secret").missingConfiguration())
                .containsExactly("KWIKI_MULTIMODAL_ENABLED=true");
        assertThat(readiness(true, "", "secret").missingConfiguration())
                .containsExactly("KWIKI_VISION_BASE_URL");
        assertThat(readiness(true, "https://vision.example/v1", "").missingConfiguration())
                .containsExactly("KWIKI_VISION_API_KEY");
        assertThat(readiness(false, "", "").missingConfiguration())
                .containsExactly("KWIKI_MULTIMODAL_ENABLED=true", "KWIKI_VISION_BASE_URL",
                        "KWIKI_VISION_API_KEY");
        assertThatThrownBy(() -> readiness(true, "", "secret")
                .requireReadyFor("kwiki-parse-2"))
                .hasMessageContaining("KWIKI_VISION_BASE_URL")
                .hasMessageNotContaining("secret");
        readiness(false, "", "").requireReadyFor("kwiki-parse-1");
        assertThat(readiness(true, "https://vision.example/v1", "secret").ready()).isTrue();
    }

    private static MultimodalSwitchReadiness readiness(boolean enabled, String url, String key) {
        MultimodalIndexingProperties multimodal = mock(MultimodalIndexingProperties.class);
        ExternalServicesProperties external = mock(ExternalServicesProperties.class);
        when(multimodal.enabled()).thenReturn(enabled);
        when(external.visionModel()).thenReturn(new ExternalServicesProperties.VisionModel(
                url, key, "qwen3.7-flash", Duration.ofSeconds(5), Duration.ofSeconds(60), 2, 4));
        return new MultimodalSwitchReadiness(multimodal, external);
    }
}
