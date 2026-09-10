package com.kwiki.rag.answer;

import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.RetrievalBudgets;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装生成上下文：按确定性的首次命中顺序排列去重的父级，受
 * 总字符预算约束。截断仅保留文本仍在上下文中的子锚点；
 * 无保留锚点的父级以及超出预算的父级会被省略。
 */
@Component
public class EvidenceAssembler {

    private final RetrievalBudgets budgets;

    public EvidenceAssembler(RetrievalBudgets budgets) {
        this.budgets = budgets;
    }

    public List<ParentEvidence> assemble(
            List<ParentEvidenceChunk> parents, long contextCharBudget) {
        budgets.validateRequest(Math.max(1, parents.size()), contextCharBudget);
        List<ParentEvidence> evidence = new ArrayList<>();
        long remaining = contextCharBudget;
        for (int order = 0; order < parents.size(); order++) {
            ParentEvidenceChunk parent = parents.get(order);
            if (remaining <= 0) {
                break;
            }
            String body = parent.content();
            boolean truncated = false;
            if (body.length() > remaining) {
                body = body.substring(0, (int) remaining);
                truncated = true;
            }
            remaining -= body.length();
            String retainedBody = body;
            var children =
                    truncated
                            ? parent.matchedChildren().stream()
                                    .filter(child -> retainedBody.contains(child.content()))
                                    .toList()
                            : parent.matchedChildren();
            if (truncated && children.isEmpty()) continue;
            evidence.add(
                    new ParentEvidence(
                            parent.parentChunkKey(),
                            parent.resourceType(),
                            parent.resourceId(),
                            parent.revisionId(),
                            parent.kbId(),
                            parent.headingPath(),
                            body,
                            truncated,
                            order,
                            children));
        }
        return evidence;
    }
}
