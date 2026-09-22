package com.kwiki.graph;

/** 全来源授权门禁的脱敏覆盖证明，只携带数量和版本，不携带正文。 */
public record GraphSourceCoverage(long expectedSourceCount, long visibleSourceCount,
                                  long selectedSourceCount, long contentEpoch,
                                  long securityEpoch, long currentContentEpoch,
                                  long currentSecurityEpoch) {
    public GraphSourceCoverage {
        if (expectedSourceCount < 0 || visibleSourceCount < 0 || selectedSourceCount < 0
                || contentEpoch < 0 || securityEpoch < 0 || currentContentEpoch < 0
                || currentSecurityEpoch < 0) {
            throw new IllegalArgumentException("来源覆盖证明无效");
        }
    }

    public boolean complete() {
        return expectedSourceCount == visibleSourceCount
                && expectedSourceCount == selectedSourceCount
                && contentEpoch == currentContentEpoch
                && securityEpoch == currentSecurityEpoch;
    }
}
