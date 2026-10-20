package com.kwiki.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 单题分层计分。POOL_50 由 BM25 与向量片段合并得到；未在回答命中的关键词组按
 * GENERATION_MISS → RECALL_MISS → RANK_MISS_20 → RANK_MISS_8 → CONTEXT_MISS 归因。
 */
public final class LayerScorer {

    private LayerScorer() {
    }

    public static CaseScore score(List<List<String>> keywords, Map<EvalLayer, List<String>> segments) {
        Map<EvalLayer, Set<Integer>> matched = new EnumMap<>(EvalLayer.class);
        for (EvalLayer layer : EvalLayer.values()) {
            List<String> texts = layer == EvalLayer.POOL_50
                    ? union(segments.get(EvalLayer.BM25_50), segments.get(EvalLayer.VECTOR_50))
                    : segments.getOrDefault(layer, List.of());
            matched.put(layer, KeywordMatcher.matchedGroups(texts, keywords));
        }
        Map<Integer, LossLayer> losses = new TreeMap<>();
        Set<Integer> gains = new TreeSet<>();
        for (int group = 0; group < keywords.size(); group++) {
            if (matched.get(EvalLayer.CONTEXT).contains(group)
                    && !matched.get(EvalLayer.POOL_50).contains(group)) {
                gains.add(group);
            }
            if (!matched.get(EvalLayer.ANSWER).contains(group)) {
                losses.put(group, attribute(group, matched));
            }
        }
        return new CaseScore(matched, keywords.size(), losses, gains);
    }

    static LossLayer attribute(int group, Map<EvalLayer, Set<Integer>> matched) {
        if (matched.get(EvalLayer.CONTEXT).contains(group)) return LossLayer.GENERATION_MISS;
        if (!matched.get(EvalLayer.POOL_50).contains(group)) return LossLayer.RECALL_MISS;
        if (!matched.get(EvalLayer.FUSED_20).contains(group)) return LossLayer.RANK_MISS_20;
        if (!matched.get(EvalLayer.FUSED_8).contains(group)) return LossLayer.RANK_MISS_8;
        return LossLayer.CONTEXT_MISS;
    }

    private static List<String> union(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first == null ? List.of() : first);
        all.addAll(second == null ? List.of() : second);
        return all;
    }
}
