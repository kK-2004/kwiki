package com.kwiki.rag.answer;

import com.kwiki.rag.orchestration.AttemptStage;
import com.kwiki.rag.retrieval.ChildEvidence;

import java.util.List;

/**
 * 服务端有界的候选回答，由保留下来的证据生成，
 * 并在任何内容发送给客户端之前经过 QA 评审。未经评审的
 * 候选的 token 绝不会到达浏览器，也绝不会被持久化为
 * 最终的对话回答。
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

    /** 评审者针对该候选实际可获得的全部 evidence。 */
    public List<ParentEvidence> effectiveEvidence() {
        return evidenceLevel == EvidenceLevel.PARENT ? parentEvidence : List.of();
    }
}
