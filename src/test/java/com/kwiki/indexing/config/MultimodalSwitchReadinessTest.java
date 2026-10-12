package com.kwiki.indexing.config;

import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.testutil.StubModelRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultimodalSwitchReadinessTest {
    @Test
    void eachMissingPrerequisiteBlocksV2WithoutExposingCredentialValues() {
        assertThat(readiness(false, true).missingConfiguration())
                .containsExactly("KWIKI_MULTIMODAL_ENABLED=true");
        assertThat(readiness(true, false).missingConfiguration())
                .containsExactly("模型配置：VISION_SUMMARY（图片摘要）");
        assertThat(readiness(false, false).missingConfiguration())
                .containsExactly("KWIKI_MULTIMODAL_ENABLED=true", "模型配置：VISION_SUMMARY（图片摘要）");
        assertThat(readiness(true, true).ready()).isTrue();
        assertThat(String.valueOf(readiness(false, false).missingConfiguration())).doesNotContain("stub-key");
    }

    private static MultimodalSwitchReadiness readiness(boolean enabled, boolean visionAssigned) {
        MultimodalIndexingProperties multimodal = mock(MultimodalIndexingProperties.class);
        when(multimodal.enabled()).thenReturn(enabled);
        StubModelRegistry models = StubModelRegistry.empty();
        if (visionAssigned) {
            models.withPurpose(ModelPurpose.VISION_SUMMARY, "qwen3.7-flash", null);
        }
        return new MultimodalSwitchReadiness(multimodal, models);
    }
}
