package com.kwiki.rag.quality;

import com.kwiki.rag.answer.ParentEvidence;

import java.util.List;

public interface QualityAnalyzerPort {
    QualityDecision analyze(String original, List<String> queries, List<ParentEvidence> evidence);
}
