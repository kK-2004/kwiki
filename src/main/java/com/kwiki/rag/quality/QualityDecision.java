package com.kwiki.rag.quality;

import java.util.*;

public record QualityDecision(
        Action action,
        boolean sufficient,
        List<String> supportedEvidenceIds,
        List<String> missingAspects,
        String reasonCode,
        List<String> suggestedQueries,
        ReturnKind returnKind) {
    public enum Action {
        GENERATE,
        RETRY,
        RETURN
    }

    public enum ReturnKind {
        NONE,
        CLARIFICATION,
        INSUFFICIENT,
        EVIDENCE
    }

    public QualityDecision {
        supportedEvidenceIds = List.copyOf(supportedEvidenceIds);
        missingAspects = List.copyOf(missingAspects);
        suggestedQueries = List.copyOf(suggestedQueries);
    }

    public static QualityDecision insufficient(String reason) {
        return new QualityDecision(
                Action.RETURN,
                false,
                List.of(),
                List.of(),
                reason,
                List.of(),
                ReturnKind.INSUFFICIENT);
    }
}
