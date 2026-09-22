package com.kwiki.graph;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 通过门禁库的社区双路召回协调。查询向量缓存按 query + 模型 + 维度隔离；
 * 每库调用固定快照的受控物理索引（分支 Top10、库内 Top3 由端口执行），
 * 最终 Top3 社区是整个请求的上限，不是每库各 3 个。零命中与分支失败分开
 * 上报：分支失败的库保留 degraded 标记，不伪装为零命中。
 */
public class CommunityRecallCoordinator {

    /** 全请求社区上限（整个请求，跨知识库）。 */
    public static final int MAX_COMMUNITIES_PER_REQUEST = 3;

    public record KbRecall(long kbId, List<CommunitySearchHit> communities,
                           boolean degraded, String degradationReason) {
        public KbRecall {
            communities = communities == null ? List.of() : List.copyOf(communities);
            degradationReason = degradationReason == null ? "" : degradationReason;
        }
    }

    public record RecallOutcome(List<KbRecall> perKnowledgeBase,
                                int totalCommunities,
                                boolean anyDegraded) {
    }

    private final CommunitySearchPort searchPort;
    private final ChunkEmbeddingPort embeddings;

    public CommunityRecallCoordinator(CommunitySearchPort searchPort,
                                      ChunkEmbeddingPort embeddings) {
        if (searchPort == null || embeddings == null) {
            throw new IllegalArgumentException("社区召回依赖不能为空");
        }
        this.searchPort = searchPort;
        this.embeddings = embeddings;
    }

    /**
     * @param embeddingCache 运行私有的 向量缓存；键由 query 与嵌入身份
     *                       （模型+维度）共同构成，绝不跨模型共享
     */
    public RecallOutcome recall(String query, Map<Long, GraphSnapshot> eligible,
                                Map<String, float[]> embeddingCache) {
        if (query == null || query.isBlank() || eligible == null || eligible.isEmpty()) {
            return new RecallOutcome(List.of(), 0, false);
        }
        float[] vector = resolveVector(query, embeddingCache);

        List<PerKbFuture> futures = new ArrayList<>();
        for (Map.Entry<Long, GraphSnapshot> entry : eligible.entrySet()) {
            GraphSnapshot snapshot = entry.getValue();
            futures.add(new PerKbFuture(entry.getKey(), CompletableFuture.supplyAsync(
                    () -> searchPort.search(new CommunitySearchRequest(
                            snapshot.kbId(), snapshot.graphVersion(),
                            snapshot.communityIndexVersion(), snapshot.communityPhysicalIndex(),
                            query, vector, MAX_COMMUNITIES_PER_REQUEST)))
                    .thenApply(result -> new KbRecall(entry.getKey(), result.hits(),
                            result.degraded(), result.degradationReason()))));
        }

        List<KbRecall> perKb = new ArrayList<>();
        for (PerKbFuture future : futures) {
            try {
                perKb.add(future.recall().join());
            } catch (RuntimeException failure) {
                perKb.add(new KbRecall(future.kbId(), List.of(), true,
                        "community-search-unavailable"));
            }
        }

        // 全请求 Top3：按分数降序、kbId/社区号稳定的全局排序。
        List<CommunitySearchHit> allHits = new ArrayList<>();
        for (KbRecall recall : perKb) {
            if (recall != null) {
                allHits.addAll(recall.communities());
            }
        }
        allHits.sort(Comparator.comparingDouble(CommunitySearchHit::score).reversed()
                .thenComparing(CommunitySearchHit::kbId)
                .thenComparing(CommunitySearchHit::communityId));
        List<CommunitySearchHit> winners = allHits.stream()
                .limit(MAX_COMMUNITIES_PER_REQUEST).toList();

        List<KbRecall> trimmed = new ArrayList<>();
        boolean anyDegraded = false;
        for (KbRecall recall : perKb) {
            anyDegraded |= recall.degraded();
            trimmed.add(new KbRecall(recall.kbId(),
                    recall.communities().stream()
                            .filter(winners::contains).toList(),
                    recall.degraded(), recall.degradationReason()));
        }
        return new RecallOutcome(List.copyOf(trimmed), winners.size(), anyDegraded);
    }

    /** kbId 与其异步召回结果的配对，失败时仍可定位知识库。 */
    private record PerKbFuture(long kbId, CompletableFuture<KbRecall> recall) {
    }

    private float[] resolveVector(String query, Map<String, float[]> embeddingCache) {
        if (embeddingCache == null) {
            return embed(query);
        }
        String cacheKey = query + "|" + embeddings.cacheIdentity();
        float[] vector = embeddingCache.computeIfAbsent(cacheKey, this::embed);
        if (vector == null) {
            embeddingCache.remove(cacheKey);
        }
        return vector;
    }

    private float[] embed(String query) {
        try {
            List<float[]> result = embeddings.embed(List.of(query));
            return result.isEmpty() ? null : result.get(0);
        } catch (Exception failure) {
            return null;
        }
    }
}
