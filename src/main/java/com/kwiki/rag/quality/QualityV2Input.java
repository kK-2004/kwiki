package com.kwiki.rag.quality;

import com.kwiki.rag.answer.CandidateAnswer;

import java.util.List;

/**
 * Input contract of the quality-v2 analyzer: the review always sees the
 * original user question, the query actually used for retrieval, the concrete
 * candidate answer, and the evidence retained for that candidate.
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
