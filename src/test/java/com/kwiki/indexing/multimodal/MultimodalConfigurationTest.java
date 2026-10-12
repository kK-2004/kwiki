package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.config.MultimodalIndexingProperties;
import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.testutil.StubModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultimodalConfigurationTest {

    private static MultimodalIndexingProperties multimodal() {
        return new MultimodalIndexingProperties(true, "v1", 20, 64, 1_000_000,
                1_000_000, 512, 1_000_000, Duration.ofSeconds(20), 3, java.util.Set.of(443));
    }

    @Test
    void missingVisionAssignmentKeepsBeanAvailableButFailsClosedOnUse() {
        ImageSummaryPort port = new MultimodalConfiguration().qwenVisionSummaryClient(
                WebClient.builder(), StubModelRegistry.empty(), multimodal(), new MultimodalMetrics(null));

        assertThat(port.modelName()).isEqualTo("unconfigured");
        assertThatThrownBy(() -> port.summarize("https://cdn.example/image.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vision model configuration is unavailable");
    }

    @Test
    void modelNameFollowsCurrentAssignment() {
        StubModelRegistry models = StubModelRegistry.empty()
                .withPurpose(ModelPurpose.VISION_SUMMARY, "qwen-vl-max", null);
        ImageSummaryPort port = new MultimodalConfiguration().qwenVisionSummaryClient(
                WebClient.builder(), models, multimodal(), new MultimodalMetrics(null));

        assertThat(port.modelName()).isEqualTo("qwen-vl-max");
    }
}
