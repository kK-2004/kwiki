package com.kwiki.rag.quality;

import java.util.List;
import java.util.Set;

/**
 * Structured output of the quality-v2 review. The three scores are independent
 * 0–1 dimensions; the server gate takes their minimum against the configured
 * threshold (default 0.80) and additionally requires passed=true, no
 * unsupported claims, and supported ids that all belong to the retained
 * evidence. RRF scores must never be interpreted as quality confidence.
 */
public record QualityAssessment(
        double relevance,
        double coverage,
        double faithfulness,
        boolean passed,
        List<String> supportedEvidenceIds,
        List<String> unsupportedClaims,
        List<String> missingAspects,
        String reasonCode,
        String reasonSummary) {

    public QualityAssessment {
        supportedEvidenceIds = List.copyOf(supportedEvidenceIds == null ? List.of() : supportedEvidenceIds);
        unsupportedClaims = List.copyOf(unsupportedClaims == null ? List.of() : unsupportedClaims);
        missingAspects = List.copyOf(missingAspects == null ? List.of() : missingAspects);
    }

    public double minScore() {
        return Math.min(relevance, Math.min(coverage, faithfulness));
    }

    /**
     * Server-side gate. {@code threshold} is inclusive on every dimension;
     * supported ids must be a subset of the retained evidence ids.
     */
    public boolean gatePasses(double threshold, Set<String> retainedEvidenceIds) {
        if (relevance < 0 || relevance > 1 || coverage < 0 || coverage > 1 || faithfulness < 0 || faithfulness > 1)
            return false;
        if (!passed) return false;
        if (minScore() < threshold) return false;
        if (!unsupportedClaims.isEmpty()) return false;
        if (supportedEvidenceIds.isEmpty()) return false;
        return retainedEvidenceIds.containsAll(supportedEvidenceIds);
    }

    public static QualityAssessment unavailable(String reasonCode, String summary) {
        return new QualityAssessment(0, 0, 0, false, List.of(), List.of(), List.of(),
                reasonCode, summary);
    }
}
