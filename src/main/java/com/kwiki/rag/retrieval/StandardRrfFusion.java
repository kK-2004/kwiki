package com.kwiki.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准倒数排名融合：score(chunk) = Σ 1 / (rankConstant + rank_i)，
 * 排名从 1 开始，按稳定的 chunk key 在每一个成功的
 * 列表上累加。Elasticsearch 的原始分数绝不进入该公式。并列时依次按
 * 最佳分支排名、BM25 排名、向量排名，最后按稳定的 chunk key 排序。
 */
public final class StandardRrfFusion {

    /** 一个具名的、按相关度降序排列的 chunk key 分级列表。 */
    public record RankedList(String branch, List<String> orderedChunkKeys) {

        public RankedList {
            orderedChunkKeys = List.copyOf(orderedChunkKeys);
        }
    }

    /** 融合候选，含其累计分数与各分支最优排名。 */
    public record FusedChunk(String chunkKey, double score,
                             Map<String, Integer> bestRankByBranch) {

        public FusedChunk {
            bestRankByBranch = Map.copyOf(bestRankByBranch);
        }
    }

    private StandardRrfFusion() {
    }

    public static List<FusedChunk> fuse(List<RankedList> lists, int rankConstant) {
        Map<String, MutableScore> scores = new LinkedHashMap<>();
        for (RankedList list : lists) {
            for (int i = 0; i < list.orderedChunkKeys().size(); i++) {
                int rank = i + 1;
                String key = list.orderedChunkKeys().get(i);
                MutableScore score = scores.computeIfAbsent(key,
                        ignored -> new MutableScore(new LinkedHashMap<>()));
                score.total += 1.0 / (rankConstant + rank);
                score.bestRanks.merge(list.branch(), rank, Math::min);
            }
        }
        List<FusedChunk> fused = new ArrayList<>();
        for (Map.Entry<String, MutableScore> entry : scores.entrySet()) {
            fused.add(new FusedChunk(entry.getKey(), entry.getValue().total,
                    entry.getValue().bestRanks));
        }
        fused.sort(java.util.Comparator
                .comparingDouble(FusedChunk::score).reversed()
                .thenComparing(chunk -> bestRank(chunk, List.of("BM25", "VECTOR")))
                .thenComparing(chunk -> chunk.bestRankByBranch().getOrDefault("BM25",
                        Integer.MAX_VALUE))
                .thenComparing(chunk -> chunk.bestRankByBranch().getOrDefault("VECTOR",
                        Integer.MAX_VALUE))
                .thenComparing(FusedChunk::chunkKey));
        return fused;
    }

    private static int bestRank(FusedChunk chunk, List<String> branches) {
        return branches.stream()
                .mapToInt(branch -> chunk.bestRankByBranch().getOrDefault(branch,
                        Integer.MAX_VALUE))
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private static final class MutableScore {
        double total;
        final Map<String, Integer> bestRanks;

        MutableScore(Map<String, Integer> bestRanks) {
            this.bestRanks = bestRanks;
        }
    }
}
