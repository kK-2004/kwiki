package com.kwiki.rag.answer;

import com.kwiki.rag.orchestration.AttemptStage;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.retrieval.ChildEvidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 由保留的 evidence 生成一个有界的候选答案。该流
 * 完全在服务端收集：未审核候选的 token 绝不
 * 触达浏览器或最终对话答案。硬性字符上限
 * （默认 32,000）将过长的生成为明确的
 * answer-too-long 失败，而非使用无界缓冲。
 */
@Component
public class CandidateGenerator {

    public static final int DEFAULT_MAX_CHARS = 32_000;

    private final AnswerLlmPort answer;
    private final int maxChars;
    private final AtomicLong candidateCounter = new AtomicLong();

    public CandidateGenerator(AnswerLlmPort answer,
                              @Value("${kwiki.agentic.candidate-max-chars:32000}") int maxChars) {
        this.answer = answer;
        this.maxChars = maxChars;
    }

    public record Generated(CandidateAnswer candidate, long elapsedMs) {}

    /**
     * @param context 组装好的生成上下文；可以是拼接的子分块
     * （子分块阶段）或父分块正文（父分块阶段）
     */
    public Generated generate(RunContext run, String originalQuery, String currentQuery,
                              AttemptStage stage, List<ChildEvidence> retainedChildren,
                              List<ParentEvidence> parentEvidence, String context) {
        run.authorize();
        run.modelCall();
        long started = System.nanoTime();
        String candidateId = "cand-" + candidateCounter.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        StringBuilder text = new StringBuilder();
        try {
            answer.streamAnswer(prompt(originalQuery, currentQuery, context))
                    .doOnNext(token -> {
                        run.check();
                        if (text.length() + token.length() > maxChars) {
                            throw new com.kwiki.rag.orchestration.RunFailure(
                                    com.kwiki.rag.orchestration.AgenticErrorCodes.ANSWER_TOO_LONG);
                        }
                        text.append(token);
                    })
                    .blockLast(run.remaining());
        } catch (com.kwiki.rag.orchestration.RunFailure e) {
            throw e;
        } catch (Exception e) {
            run.check();
            throw new com.kwiki.rag.orchestration.RunFailure(
                    com.kwiki.rag.orchestration.AgenticErrorCodes.ANSWER_PROVIDER_FAILED);
        }
        run.authorize();
        if (text.toString().isBlank()) {
            throw new com.kwiki.rag.orchestration.RunFailure(
                    com.kwiki.rag.orchestration.AgenticErrorCodes.ANSWER_PROVIDER_FAILED);
        }
        var candidate = new CandidateAnswer(
                candidateId,
                text.toString(),
                stage.isParentStage()
                        ? CandidateAnswer.EvidenceLevel.PARENT
                        : CandidateAnswer.EvidenceLevel.CHILD,
                stage,
                retainedChildren,
                parentEvidence);
        return new Generated(candidate, (System.nanoTime() - started) / 1_000_000);
    }

    private String prompt(String originalQuery, String currentQuery, String context) {
        return "Question: " + originalQuery + "\nRetrieval query: " + currentQuery
                + "\nEvidence:\n" + context;
    }
}
