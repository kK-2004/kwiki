package com.kwiki.rag.answer;

import com.kwiki.rag.orchestration.AttemptStage;
import com.kwiki.rag.retrieval.ChildEvidence;

import java.util.List;

/**
 * A server-side bounded candidate answer produced from retained evidence and
 * evaluated by QA before anything is sent to the client. Tokens of an
 * un-reviewed candidate never reach the browser and are never persisted as the
 * final conversation answer.
 */
public record CandidateAnswer(
        String candidateId,
        String content,
        EvidenceLevel evidenceLevel,
        AttemptStage attemptStage,
        List<ChildEvidence> retainedChildren,
        List<ParentEvidence> parentEvidence) {

    public enum EvidenceLevel {
        CHILD, PARENT
    }

    public CandidateAnswer {
        candidateId = candidateId == null || candidateId.isBlank()
                ? "cand-" + System.nanoTime()
                : candidateId;
        retainedChildren = List.copyOf(retainedChildren == null ? List.of() : retainedChildren);
        parentEvidence = List.copyOf(parentEvidence == null ? List.of() : parentEvidence);
    }

    public int contentLength() {
        return content == null ? 0 : content.length();
    }

    /** All evidence actually available to the reviewer for this candidate. */
    public List<ParentEvidence> effectiveEvidence() {
        return evidenceLevel == EvidenceLevel.PARENT ? parentEvidence : List.of();
    }
}
