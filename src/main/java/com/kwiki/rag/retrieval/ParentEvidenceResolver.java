package com.kwiki.rag.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按排名顺序遍历融合后的子候选，按 parentChunkKey 分组，并批量拉取
 * 已授权的父级（至多一次）。首子命中顺序优先；缺失或未授权的父级（及其
 * 子级）会被省略，不会用超作用域内容替代。
 */
@Component
public class ParentEvidenceResolver {

    /** 批量获取父分块的端口；实现方会重新应用作用域过滤。 */
    public interface ParentChunkFetcher {
        List<ParentEvidenceChunk> fetchByKeys(
                List<String> parentChunkKeys, ScopeFilter scopeFilter);
    }

    private final ParentChunkFetcher parents;

    public ParentEvidenceResolver(Optional<ParentChunkFetcher> parents) {
        this.parents = parents.orElse(null);
    }

    public List<ParentEvidenceChunk> resolve(
            List<StandardRrfFusion.FusedChunk> fusedChildren,
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
            childrenByParent
                    .computeIfAbsent(hit.parentChunkKey(), key -> new ArrayList<>())
                    .add(hit);
            bestScoreByParent.merge(hit.parentChunkKey(), fused.score(), Math::max);
        }
        if (childrenByParent.isEmpty()) {
            return List.of();
        }
        Map<String, ParentEvidenceChunk> fetched = new LinkedHashMap<>();
        for (ParentEvidenceChunk parent :
                parents.fetchByKeys(new ArrayList<>(childrenByParent.keySet()), scopeFilter)) {
            fetched.put(parent.parentChunkKey(), parent);
        }

        List<ParentEvidenceChunk> evidence = new ArrayList<>();
        for (Map.Entry<String, List<ChunkHit>> entry : childrenByParent.entrySet()) {
            ParentEvidenceChunk parent = fetched.get(entry.getKey());
            if (parent == null) {
                continue; // 缺失或已不再授权：静默忽略
            }
            List<ChunkHit> validChildren =
                    entry.getValue().stream()
                            .filter(
                                    child ->
                                            child.kbId() == parent.kbId()
                                                    && child.resourceId() == parent.resourceId()
                                                    && java.util.Objects.equals(
                                                            child.revisionId(), parent.revisionId())
                                                    && child.resourceType()
                                                            .equals(parent.resourceType()))
                            .toList();
            if (validChildren.isEmpty()) continue;
            evidence.add(
                    new ParentEvidenceChunk(
                            parent.parentChunkKey(),
                            parent.kbId(),
                            parent.resourceType(),
                            parent.resourceId(),
                            parent.revisionId(),
                            parent.headingPath(),
                            parent.content(),
                            bestScoreByParent.getOrDefault(entry.getKey(), 0.0),
                            validChildren));
            if (evidence.size() == parentLimit) {
                break;
            }
        }
        return evidence;
    }
}
