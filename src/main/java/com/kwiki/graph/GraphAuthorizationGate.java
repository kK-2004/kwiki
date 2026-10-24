package com.kwiki.graph;

/**
 * 在社区搜索、摘要和任何图扩展前执行全来源覆盖与 epoch 门禁，并给出三档判定：
 * <ul>
 *   <li>{@link Mode#FULL}：epoch 一致、无已退役来源、有效来源全部可读且在选择范围内——行为与全量一致；</li>
 *   <li>{@link Mode#FACTS_ONLY}：有效来源全部可读且在选择范围内，但快照 epoch 落后或清单含已退役来源——
 *       停用社区召回与代表实体路径，仅允许从 Chunk 种子出发的 seed-only 扩展；</li>
 *   <li>{@link Mode#OFF}：某个有效来源用户不可读或不在选择范围内（或快照/版本不可用）——整条图路径关闭。</li>
 * </ul>
 * 判定顺序即 OFF → FACTS_ONLY → FULL；security epoch 落后不直接判 OFF——
 * 门禁每次都按当前权限实时核验全部有效来源，权限收紧落入 OFF，
 * 成员变化不影响安全性（摘要文本不进入模型输入，FACTS_ONLY 不使用社区）。
 */
public final class GraphAuthorizationGate {

    public enum Mode {
        /** 快照与当前一致，可使用社区召回与全部扩展路径。 */
        FULL,
        /** 快照 epoch 落后或含已退役来源：只允许 seed-only 扩展，不使用社区。 */
        FACTS_ONLY,
        /** 有效来源不可读或选择范围不完整：不查社区、不做任何图扩展。 */
        OFF
    }

    /**
     * 门禁判定结果。{@code allowed()} 表示图路径是否仍然开启（FULL 或 FACTS_ONLY）；
     * 只有 FULL 可使用社区与代表实体路径。
     */
    public record Decision(Mode mode, String reason) {
        public static Decision permit() { return new Decision(Mode.FULL, null); }
        public static Decision denied(String reason) { return new Decision(Mode.OFF, reason); }

        /** FACTS_ONLY 降级判定；reason 为降级原因。 */
        public static Decision factsOnly(String reason) { return new Decision(Mode.FACTS_ONLY, reason); }

        /** 图路径是否开启（FULL 或 FACTS_ONLY）；OFF 时整条图增强路径关闭。 */
        public boolean allowed() { return mode != Mode.OFF; }

        /** 是否可使用社区召回与代表实体路径。 */
        public boolean communityAllowed() { return mode == Mode.FULL; }
    }

    public Decision check(GraphSourceCoverage coverage, GraphSnapshot snapshot,
                          long selectedChunkIndexVersion) {
        if (coverage == null || snapshot == null) {
            return Decision.denied("graph-snapshot-unavailable");
        }
        if (snapshot.chunkIndexVersion() != selectedChunkIndexVersion) {
            return Decision.denied("version-incompatible");
        }
        if (coverage.expectedSourceCount() != coverage.visibleSourceCount()
                || coverage.expectedSourceCount() != coverage.selectedSourceCount()) {
            return Decision.denied("source-coverage-incomplete");
        }
        if (coverage.factsOnly()) {
            return coverage.retiredSourceCount() > 0
                    ? Decision.factsOnly("sources-retired")
                    : Decision.factsOnly("source-epoch-stale");
        }
        return Decision.permit();
    }
}
