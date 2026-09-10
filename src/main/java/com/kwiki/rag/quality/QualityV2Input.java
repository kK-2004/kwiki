package com.kwiki.rag.quality;

import com.kwiki.rag.answer.CandidateAnswer;

import java.util.List;

/**
 * quality-v2 分析器的输入契约：评审始终能看到
 * 原始用户问题、实际用于检索的查询、具象的
 * 候选答案，以及为该候选保留的 evidence。
 */
public record QualityV2Input(
        String originalQuery,
        String currentQuery,
        CandidateAnswer candidate,
        List<String> evidenceSummaries) {

    public QualityV2Input {
        evidenceSummaries = List.copyOf(evidenceSummaries == null ? List.of() : evidenceSummaries);
    }
}
