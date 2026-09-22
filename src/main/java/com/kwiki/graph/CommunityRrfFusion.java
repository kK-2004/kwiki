package com.kwiki.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 社区 BM25/vector 两分支的独立 RRF；不与 Chunk 分数直接相加。 */
public final class CommunityRrfFusion {

    public static final int DEFAULT_K = 60;

    public record RankedHit(CommunitySearchHit hit, int rank) {}

    private CommunityRrfFusion() {
    }

    public static List<CommunitySearchHit> fuse(List<RankedHit> bm25,
                                                List<RankedHit> vector, int limit) {
        Map<String, Score> scores = new LinkedHashMap<>();
        add(scores, bm25);
        add(scores, vector);
        return scores.values().stream()
                .sorted(Comparator.comparingDouble(Score::value).reversed()
                        .thenComparing(score -> score.hit().communityId()))
                .limit(limit)
                .map(score -> score.hit())
                .toList();
    }

    private static void add(Map<String, Score> scores, List<RankedHit> branch) {
        if (branch == null) return;
        for (RankedHit ranked : branch) {
            if (ranked == null || ranked.hit() == null || ranked.rank() < 1) continue;
            Score score = scores.computeIfAbsent(ranked.hit().communityId(),
                    ignored -> new Score(ranked.hit(), 0));
            score.add(1.0 / (DEFAULT_K + ranked.rank()));
        }
    }

    private static final class Score {
        private final CommunitySearchHit hit;
        private double value;
        private Score(CommunitySearchHit hit, double value) {
            this.hit = hit;
            this.value = value;
        }
        private void add(double increment) { value += increment; }
        private CommunitySearchHit hit() { return hit; }
        private double value() { return value; }
    }
}
