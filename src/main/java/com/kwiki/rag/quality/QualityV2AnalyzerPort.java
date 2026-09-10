package com.kwiki.rag.quality;

/**
 * Quality-v2 analyzer: evaluates the concrete candidate answer that would be
 * published, not just the evidence. Implementations return a structurally
 * validated {@link QualityAssessment}; format failures must surface as
 * {@code qa-unavailable} rather than a content-quality verdict.
 */
public interface QualityV2AnalyzerPort {

    QualityAssessment assess(QualityV2Input input);
}
