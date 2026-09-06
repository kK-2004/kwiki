package com.kwiki.rag.retrieval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Validated retrieval budgets. Out-of-range requests are rejected instead of
 * silently widening work: child branch TopK, fused child count, distinct parent
 * count, subquery count, deadlines, and the total parent-context character budget.
 */
@Component
public class RetrievalBudgets {

    public final int childBranchTopK;
    public final int fusedChildLimit;
    public final int distinctParentLimit;
    public final int maxSubqueries;
    public final long parentContextCharBudget;
    public final Duration branchDeadline;

    public RetrievalBudgets(
            @Value("${kwiki.retrieval.child-branch-topk:50}") int childBranchTopK,
            @Value("${kwiki.retrieval.fused-child-limit:40}") int fusedChildLimit,
            @Value("${kwiki.retrieval.distinct-parent-limit:8}") int distinctParentLimit,
            @Value("${kwiki.retrieval.max-subqueries:3}") int maxSubqueries,
            @Value("${kwiki.retrieval.parent-context-chars:24000}") long parentContextCharBudget,
            @Value("${kwiki.retrieval.branch-deadline:5s}") Duration branchDeadline) {
        if (childBranchTopK < 1 || childBranchTopK > 200) {
            throw new IllegalArgumentException("child-branch-topk out of range");
        }
        if (fusedChildLimit < 1 || fusedChildLimit > childBranchTopK) {
            throw new IllegalArgumentException("fused-child-limit out of range");
        }
        if (distinctParentLimit < 1 || distinctParentLimit > 50) {
            throw new IllegalArgumentException("distinct-parent-limit out of range");
        }
        if (maxSubqueries < 1 || maxSubqueries > 3) {
            throw new IllegalArgumentException("max-subqueries out of range");
        }
        if (parentContextCharBudget < 1000 || parentContextCharBudget > 200_000) {
            throw new IllegalArgumentException("parent-context budget out of range");
        }
        if (branchDeadline.isNegative() || branchDeadline.isZero()) {
            throw new IllegalArgumentException("branch deadline must be positive");
        }
        this.childBranchTopK = childBranchTopK;
        this.fusedChildLimit = fusedChildLimit;
        this.distinctParentLimit = distinctParentLimit;
        this.maxSubqueries = maxSubqueries;
        this.parentContextCharBudget = parentContextCharBudget;
        this.branchDeadline = branchDeadline;
    }

    /** Callers requesting more than the configured limits are rejected, never widened. */
    public void validateRequest(int requestedParentCount, long requestedContextChars) {
        if (requestedParentCount < 1 || requestedParentCount > distinctParentLimit) {
            throw new IllegalArgumentException(
                    "requested parent count out of range: " + requestedParentCount);
        }
        if (requestedContextChars <= 0 || requestedContextChars > parentContextCharBudget) {
            throw new IllegalArgumentException(
                    "requested context budget out of range: " + requestedContextChars);
        }
    }
}
