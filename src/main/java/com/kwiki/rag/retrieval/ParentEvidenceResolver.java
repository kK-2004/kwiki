package com.kwiki.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Walks fused child candidates in rank order, groups them by parentChunkKey,
 * and batch-fetches authorized parents at most once. First-child order wins;
 * missing or unauthorized parents (and their children) are omitted without
 * substituting out-of-scope content.
 */
@Component
public class ParentEvidenceResolver {

    /** Port for batch parent fetch; implementations re-apply the scope filter. */
    public interface ParentChunkFetcher {
        List<ParentEvidenceChunk> fetchByKeys(List<String> parentChunkKeys,
                                              ScopeFilter scopeFilter);
    }

    private final ParentChunkFetcher parents;

    public ParentEvidenceResolver(Optional<ParentChunkFetcher> parents) {
        this.parents = parents.orElse(null);
    }

    public List<ParentEvidenceChunk> resolve(List<StandardRrfFusion.FusedChunk> fusedChildren,
                                              Map<String, ChunkHit> hitsByKey,
                                              ScopeFilter scopeFilter,
                                              int parentLimit) {
        if (parents == null || fusedChildren.isEmpty()) {
            return List.of();
        }
        Map<String, List<ChunkHit>> childrenByParent = new LinkedHashMap<>();
        Map<String, Double> bestScoreByParent = new LinkedHashMap<>();
        for (StandardRrfFusion.FusedChunk fused : fusedChildren) {
            ChunkHit hit = hitsByKey.get(fused.chunkKey());
            if (hit == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(hit.parentChunkKey(),
                    key -> new ArrayList<>()).add(hit);
            bestScoreByParent.merge(hit.parentChunkKey(), fused.score(), Math::max);
        }
        if (childrenByParent.isEmpty()) {
            return List.of();
        }
        Map<String, ParentEvidenceChunk> fetched = new LinkedHashMap<>();
        for (ParentEvidenceChunk parent : parents.fetchByKeys(
                new ArrayList<>(childrenByParent.keySet()), scopeFilter)) {
            fetched.put(parent.parentChunkKey(), parent);
        }

        List<ParentEvidenceChunk> evidence = new ArrayList<>();
        for (Map.Entry<String, List<ChunkHit>> entry : childrenByParent.entrySet()) {
            ParentEvidenceChunk parent = fetched.get(entry.getKey());
            if (parent == null) {
                continue; // missing or no longer authorized: omit silently
            }
            evidence.add(new ParentEvidenceChunk(parent.parentChunkKey(), parent.kbId(),
                    parent.resourceType(), parent.resourceId(), parent.revisionId(),
                    parent.headingPath(), parent.content(),
                    bestScoreByParent.getOrDefault(entry.getKey(), 0.0),
                    entry.getValue()));
            if (evidence.size() == parentLimit) {
                break;
            }
        }
        return evidence;
    }
}
