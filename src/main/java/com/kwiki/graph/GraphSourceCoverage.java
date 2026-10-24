package com.kwiki.graph;

/**
 * 全来源授权门禁的脱敏覆盖证明，只携带数量和版本，不携带正文。
 * expected/visible/selected 只统计当前仍有效的来源（当前有效 ∪ 快照清单中仍有效）；
 * 快照清单中已退役的来源（不再 ACTIVE/STORED 或已被物理删除）只计入
 * retiredSourceCount，不参与可读性与选择范围校验——它使门禁降为 FACTS_ONLY
 * 而不是关闭。
 */
public record GraphSourceCoverage(long expectedSourceCount, long visibleSourceCount,
                                  long selectedSourceCount, long retiredSourceCount,
                                  long contentEpoch,
                                  long securityEpoch, long currentContentEpoch,
                                  long currentSecurityEpoch) {
    public GraphSourceCoverage {
        if (expectedSourceCount < 0 || visibleSourceCount < 0 || selectedSourceCount < 0
                || retiredSourceCount < 0
                || contentEpoch < 0 || securityEpoch < 0 || currentContentEpoch < 0
                || currentSecurityEpoch < 0) {
            throw new IllegalArgumentException("来源覆盖证明无效");
        }
    }

    /** 全部有效来源可读、在范围内且 epoch 一致（不含已退役来源的影响）。 */
    public boolean complete() {
        return expectedSourceCount == visibleSourceCount
                && expectedSourceCount == selectedSourceCount
                && contentEpoch == currentContentEpoch
                && securityEpoch == currentSecurityEpoch;
    }

    /** 快照落后于当前 epoch 或清单中存在已退役来源：门禁降为 FACTS_ONLY。 */
    public boolean factsOnly() {
        return contentEpoch != currentContentEpoch
                || securityEpoch != currentSecurityEpoch
                || retiredSourceCount > 0;
    }
}
