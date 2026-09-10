package com.kwiki.rag.retrieval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validated budgets of the QA-gated knowledge path, split per stage. The
 * expanded stage widens both branches and the fused TopK; the parent budget
 * stays fixed so an expanded TopK cannot grow the parent context without
 * bound. finalTopK must never exceed branchTopK — a fused result larger than
 * its own branch candidate set is a configuration error.
 */
@Component
public class QaRetrievalBudgets {

    public record StageBudget(int branchTopK, int finalTopK, int parentLimit,
                              long childCharBudget) {}

    public final int baseBranchTopK;
    public final int baseFinalTopK;
    public final int expandedBranchTopK;
    public final int expandedFinalTopK;
    public final int baseParentLimit;
    public final int expandedParentLimit;
    public final long baseChildCharBudget;
    public final long expandedChildCharBudget;
    public final long parentCharBudget;

    public QaRetrievalBudgets(
            @Value("${kwiki.retrieval.qa.base-branch-topk:20}") int baseBranchTopK,
            @Value("${kwiki.retrieval.qa.base-final-topk:8}") int baseFinalTopK,
            @Value("${kwiki.retrieval.qa.expanded-branch-topk:50}") int expandedBranchTopK,
            @Value("${kwiki.retrieval.qa.expanded-final-topk:20}") int expandedFinalTopK,
            @Value("${kwiki.retrieval.qa.base-parent-limit:8}") int baseParentLimit,
            @Value("${kwiki.retrieval.qa.expanded-parent-limit:16}") int expandedParentLimit,
            @Value("${kwiki.retrieval.qa.base-child-chars:12000}") long baseChildCharBudget,
            @Value("${kwiki.retrieval.qa.expanded-child-chars:24000}") long expandedChildCharBudget,
            @Value("${kwiki.retrieval.qa.parent-chars:24000}") long parentCharBudget) {
        if (baseBranchTopK < 1 || baseBranchTopK > 200
                || expandedBranchTopK < baseBranchTopK || expandedBranchTopK > 200) {
            throw new IllegalArgumentException("qa branch topk out of range");
        }
        if (baseFinalTopK < 1 || baseFinalTopK > baseBranchTopK
                || expandedFinalTopK < baseFinalTopK || expandedFinalTopK > expandedBranchTopK) {
            throw new IllegalArgumentException("qa final topk must satisfy finalTopK <= branchTopK");
        }
        if (baseParentLimit < 1 || expandedParentLimit < baseParentLimit
                || expandedParentLimit > 50) {
            throw new IllegalArgumentException("qa parent limit out of range");
        }
        if (baseChildCharBudget < 1000 || expandedChildCharBudget < baseChildCharBudget
                || parentCharBudget < 1000) {
            throw new IllegalArgumentException("qa context budget out of range");
        }
        this.baseBranchTopK = baseBranchTopK;
        this.baseFinalTopK = baseFinalTopK;
        this.expandedBranchTopK = expandedBranchTopK;
        this.expandedFinalTopK = expandedFinalTopK;
        this.baseParentLimit = baseParentLimit;
        this.expandedParentLimit = expandedParentLimit;
        this.baseChildCharBudget = baseChildCharBudget;
        this.expandedChildCharBudget = expandedChildCharBudget;
        this.parentCharBudget = parentCharBudget;
    }

    public StageBudget base() {
        return new StageBudget(baseBranchTopK, baseFinalTopK, baseParentLimit,
                baseChildCharBudget);
    }

    public StageBudget expanded() {
        return new StageBudget(expandedBranchTopK, expandedFinalTopK, expandedParentLimit,
                expandedChildCharBudget);
    }
}
