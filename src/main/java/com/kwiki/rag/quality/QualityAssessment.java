package com.kwiki.rag.quality;

import java.util.List;
import java.util.Set;

/**
 * quality-v2 评审的结构化输出。三个分数是彼此独立的
 * 0–1 维度；服务端门禁取它们相对已配置阈值
 * （默认 0.80）的最小值，并额外要求 passed=true、
 * 不存在无依据的论断，且 supported id 全部属于保留下来的
 * 证据。RRF 分数绝不能被解读为质量置信度。
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
     * 服务端门控。{@code threshold} 对每个维度都取闭区间；
     * supported id 必须是保留 evidence id 的子集。
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
