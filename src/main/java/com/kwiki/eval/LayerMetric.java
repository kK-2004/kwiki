package com.kwiki.eval;

/** 某一层的关键词覆盖率均值、完全命中率与参与题数。 */
public record LayerMetric(double coverage, double fullHit, int cases) {
}
