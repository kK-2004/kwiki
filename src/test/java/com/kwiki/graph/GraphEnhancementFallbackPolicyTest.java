package com.kwiki.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 图增强回退策略：FACTS_ONLY 不使用社区，有种子才 seed-only，无种子跳过。 */
class GraphEnhancementFallbackPolicyTest {

    @Test
    void factsOnlyUsesSeedOnlyOrSkipsWithoutCommunity() {
        assertThat(GraphEnhancementFallbackPolicy.resolve(true, true, true, true, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.SEED_ONLY);
        assertThat(GraphEnhancementFallbackPolicy.resolve(true, true, false, true, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.SKIP);
        // 社区服务失败不影响 FACTS_ONLY 的判定路径（本就不使用社区）
        assertThat(GraphEnhancementFallbackPolicy.resolve(true, true, true, false, true))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.SEED_ONLY);
    }

    @Test
    void gateStillClosedForUnauthorizedKnowledgeBases() {
        assertThat(GraphEnhancementFallbackPolicy.resolve(false, true, true, true, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.OFF);
        assertThat(GraphEnhancementFallbackPolicy.resolve(false, false, true, true, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.OFF);
    }

    @Test
    void fullModeKeepsCommunitySeededAndRepresentativePaths() {
        assertThat(GraphEnhancementFallbackPolicy.resolve(true, false, true, true, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.COMMUNITY_SEEDED);
        assertThat(GraphEnhancementFallbackPolicy.resolve(true, false, true, false, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.SEED_ONLY);
        assertThat(GraphEnhancementFallbackPolicy.resolve(true, false, false, true, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.REPRESENTATIVE_ENTITY);
        assertThat(GraphEnhancementFallbackPolicy.resolve(true, false, false, false, false))
                .isEqualTo(GraphEnhancementFallbackPolicy.Mode.SKIP);
    }
}
