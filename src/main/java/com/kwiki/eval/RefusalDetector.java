package com.kwiki.eval;

import com.kwiki.rag.orchestration.AgenticErrorCodes;

import java.util.List;

/** 负样本拒答判定：终态为证据不足，或回答含任一拒答短语（归一化后匹配）。 */
public final class RefusalDetector {

    private final List<String> phrases;

    public RefusalDetector(List<String> phrases) {
        this.phrases = phrases.stream().map(KeywordMatcher::normalize)
                .filter(phrase -> !phrase.isEmpty()).toList();
    }

    public boolean refused(String outcome, String answer) {
        if (AgenticErrorCodes.INSUFFICIENT.equals(outcome)) return true;
        String normalized = KeywordMatcher.normalize(answer);
        return phrases.stream().anyMatch(normalized::contains);
    }
}
