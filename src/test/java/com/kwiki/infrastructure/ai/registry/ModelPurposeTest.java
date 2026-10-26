package com.kwiki.infrastructure.ai.registry;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPurposeTest {

    @Test
    void 默认温度与迁移前的硬编码一致() {
        for (ModelPurpose purpose : EnumSet.of(ModelPurpose.AGENT_PLANNER, ModelPurpose.RETRIEVAL_PLANNER,
                ModelPurpose.ROUTER, ModelPurpose.REWRITE, ModelPurpose.DIRECT_ANSWER,
                ModelPurpose.QUALITY_ANALYZER, ModelPurpose.GRAPH_EXTRACTION,
                ModelPurpose.GRAPH_SUMMARY, ModelPurpose.VISION_SUMMARY)) {
            assertThat(purpose.defaultTemperature()).as(purpose.name()).isEqualTo(0.0);
        }
        assertThat(ModelPurpose.ANSWER.defaultTemperature()).isEqualTo(0.2);
    }

    @Test
    void 标签与工具要求() {
        assertThat(ModelPurpose.VISION_SUMMARY.requiredTag()).isEqualTo(ModelTag.VISION);
        assertThat(EnumSet.allOf(ModelPurpose.class).stream()
                .filter(p -> p != ModelPurpose.VISION_SUMMARY)
                .allMatch(p -> p.requiredTag() == ModelTag.CHAT)).isTrue();
        assertThat(EnumSet.allOf(ModelPurpose.class).stream().filter(ModelPurpose::requiresTools))
                .containsExactlyInAnyOrder(ModelPurpose.AGENT_PLANNER, ModelPurpose.RETRIEVAL_PLANNER);
    }

    @Test
    void 标签数据库取值可往返() {
        for (ModelTag tag : ModelTag.values()) {
            assertThat(ModelTag.fromDb(tag.dbValue())).isEqualTo(tag);
        }
        assertThat(ModelTag.EMBEDDING.dbValue()).isEqualTo("embedding");
    }
}
