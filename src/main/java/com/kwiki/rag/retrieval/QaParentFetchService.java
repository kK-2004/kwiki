package com.kwiki.rag.retrieval;

import com.kwiki.rag.answer.EvidenceAssembler;
import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.ScopeVersionService;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parent fetch is a separate, explicitly requested stage of the QA-gated
 * path: only after a child-stage candidate failed its gate does the workflow
 * fetch the parents of the children retained for THAT stage. Parents are
 * deduplicated by first-child-hit order, bounded by the stage parent limit and
 * the fixed parent character budget, and every fetch re-validates the
 * authorization scope and lifecycle exclusions. When the retained children's
 * parents are all already in the used context, the stage reports skipped
 * instead of re-serving identical context.
 */
@Component
public class QaParentFetchService {

    public record ParentStageOutcome(List<ParentEvidence> evidence, boolean skipped,
                                     String reason, int parentCount, long elapsedMs) {}

    private final ParentEvidenceResolver resolver;
    private final EvidenceAssembler assembler;
    private final ScopeVersionService scopeVersions;

    public QaParentFetchService(ParentEvidenceResolver resolver,
                                EvidenceAssembler assembler,
                                ScopeVersionService scopeVersions) {
        this.resolver = resolver;
        this.assembler = assembler;
        this.scopeVersions = scopeVersions;
    }

    /**
     * @param usedParentKeys parent keys already present in this query's context
     *                       (child stages carry none by construction)
     */
    public ParentStageOutcome fetchParents(AuthorizationScope scope, RunContext run,
                                           List<ChildEvidence> retainedChildren,
                                           int parentLimit, long parentCharBudget,
                                           Set<String> usedParentKeys) {
        long started = System.nanoTime();
        authorize(scope);
        run.authorize();
        var filter = ScopeFilter.from(scope);

        Map<String, ChildEvidence> childrenByKey = new LinkedHashMap<>();
        List<StandardRrfFusion.FusedChunk> fused = new ArrayList<>();
        Map<String, ChunkHit> hitsByKey = new LinkedHashMap<>();
        for (ChildEvidence child : retainedChildren) {
            childrenByKey.putIfAbsent(child.chunkKey(), child);
            fused.add(new StandardRrfFusion.FusedChunk(
                    child.chunkKey(), child.rrfScore(), Map.of()));
            hitsByKey.putIfAbsent(child.chunkKey(), toHit(child));
        }

        var parentChunks = resolver.resolve(fused, hitsByKey, filter, parentLimit);
        authorize(scope);
        run.authorize();

        boolean allUsed = !parentChunks.isEmpty()
                && parentChunks.stream()
                        .allMatch(parent -> usedParentKeys.contains(parent.parentChunkKey()));
        if (allUsed || parentChunks.isEmpty()) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return new ParentStageOutcome(List.of(), true,
                    parentChunks.isEmpty() ? "no-parents" : "no-new-parents",
                    0, elapsed);
        }
        var evidence = assembler.assemble(parentChunks, parentCharBudget);
        authorize(scope);
        run.authorize();
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new ParentStageOutcome(evidence, false, null, evidence.size(), elapsed);
    }

    /** Parent keys currently represented in the evidence list. */
    public static Set<String> keysOf(List<ParentEvidence> evidence) {
        Set<String> keys = new HashSet<>();
        for (ParentEvidence parent : evidence) {
            keys.add(parent.parentChunkKey());
        }
        return keys;
    }

    private ChunkHit toHit(ChildEvidence child) {
        return new ChunkHit(
                child.chunkKey(), child.parentChunkKey(), child.kbId(),
                child.resourceType(), child.resourceId(), child.revisionId(),
                child.headingPath(), child.charStart(), child.charEnd(), child.content());
    }

    private void authorize(AuthorizationScope scope) {
        if (scope == null || scope.isStale(scopeVersions::current)) {
            throw new HybridRetrievalOrchestrator.StaleScopeException();
        }
    }
}
