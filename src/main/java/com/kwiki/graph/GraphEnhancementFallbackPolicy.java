package com.kwiki.graph;

/**
 * 图增强回退策略。只有通过全来源授权门禁的知识库才允许 seed-only 回退；
 * 权限或选择范围不完整的库整条图增强路径关闭。只有社区命中而没有 Chunk
 * 种子时，必须先加载并验证代表实体的原文，没有可用原文不能只用摘要回答。
 * FACTS_ONLY（快照 epoch 落后或清单含已退役来源）的库不使用社区：
 * 有 Chunk 种子才做同预算 seed-only 扩展，否则跳过。
 */
public final class GraphEnhancementFallbackPolicy {

    public enum Mode {
        /** 门禁未通过：不查社区、不使用摘要、不做任何图扩展。 */
        OFF,
        /** 有 Chunk 种子且社区命中：以社区约束 + 种子起步遍历。 */
        COMMUNITY_SEEDED,
        /** 完整授权库的社区零命中/服务故障/种子与社区无交集：同预算 seed-only 扩展。 */
        SEED_ONLY,
        /** 只有社区命中且无 Chunk 种子：先验证代表实体原文，原文可用才扩展。 */
        REPRESENTATIVE_ENTITY,
        /** 无种子且无可用社区：跳过图增强，继续 Chunk 路径。 */
        SKIP
    }

    private GraphEnhancementFallbackPolicy() {
    }

    /**
     * @param gateAllowed            该库是否通过全来源覆盖门禁（FULL 或 FACTS_ONLY）
     * @param factsOnly              门禁档位是否为 FACTS_ONLY（epoch 落后或含已退役来源）
     * @param chunkSeedsPresent      是否存在来自 READY ES entityIds 的有效种子
     * @param communityHits          社区召回是否有命中（服务失败不算命中）
     * @param communityServiceFailed 社区分支是否服务失败
     */
    public static Mode resolve(boolean gateAllowed, boolean factsOnly, boolean chunkSeedsPresent,
                               boolean communityHits, boolean communityServiceFailed) {
        if (!gateAllowed) {
            return Mode.OFF;
        }
        if (factsOnly) {
            // 降级库不查询社区索引，也不走代表实体路径；每条关系会逐条回查原文
            return chunkSeedsPresent ? Mode.SEED_ONLY : Mode.SKIP;
        }
        if (chunkSeedsPresent) {
            // 社区服务故障或零命中：完整授权库允许同预算 seed-only。
            return communityHits && !communityServiceFailed
                    ? Mode.COMMUNITY_SEEDED : Mode.SEED_ONLY;
        }
        if (communityHits && !communityServiceFailed) {
            return Mode.REPRESENTATIVE_ENTITY;
        }
        return Mode.SKIP;
    }
}
