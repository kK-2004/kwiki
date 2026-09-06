package com.kwiki.rag.answer;

import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the generation context: distinct parents in deterministic first-hit
 * order, bounded by the total character budget. Truncation shortens the LAST
 * parent's body instead of dropping it, so every matched child keeps a citation
 * anchor; a zero-remainder budget drops further parents entirely.
 */
@Component
public class EvidenceAssembler {

    private final RetrievalBudgets budgets;

    public EvidenceAssembler(RetrievalBudgets budgets) {
        this.budgets = budgets;
    }

    public List<ParentEvidence> assemble(List<ParentEvidenceChunk> parents, long contextCharBudget) {
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
            evidence.add(new ParentEvidence(parent.parentChunkKey(), parent.resourceType(),
                    parent.resourceId(), parent.revisionId(), parent.kbId(), parent.headingPath(),
                    body, truncated, order, parent.matchedChildren()));
        }
        return evidence;
    }
}
