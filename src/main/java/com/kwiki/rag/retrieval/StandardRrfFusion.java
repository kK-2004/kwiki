package com.kwiki.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard reciprocal rank fusion: score(chunk) = Σ 1 / (rankConstant + rank_i)
 * with ranks starting at one, accumulated across every successful list by stable
 * chunk key. Elasticsearch raw scores never enter the formula. Ties break by
 * best branch rank, BM25 rank, vector rank, then the stable chunk key.
 */
public final class StandardRrfFusion {

    /** A named ranked list of chunk keys, most relevant first. */
    public record RankedList(String branch, List<String> orderedChunkKeys) {

        public RankedList {
            orderedChunkKeys = List.copyOf(orderedChunkKeys);
        }
    }

    /** Fused candidate with its accumulated score and per-branch best ranks. */
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
