package com.kwiki.eval;

import java.util.List;
import java.util.Set;

/**
 * 一道评测题。keywords 是关键词组列表，组内为同义别名，命中任一别名即该组命中；
 * negative=true 表示无关无解问，必须没有关键词。tier 为该题首次纳入的层级。
 */
public record EvalCase(
        String id,
        String source,
        int tier,
        String category,
        String domain,
        String query,
        List<List<String>> keywords,
        boolean negative,
        List<Integer> sourceDocIds,
        List<String> keypoints) {

    private static final Set<Integer> TIERS = Set.of(50, 100, 200);

    public EvalCase {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("eval case id is blank");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("eval case " + id + " query is blank");
        if (!TIERS.contains(tier)) throw new IllegalArgumentException("eval case " + id + " tier must be 50/100/200");
        keywords = keywords == null ? List.of() : keywords.stream().map(List::copyOf).toList();
        if (negative && !keywords.isEmpty()) {
            throw new IllegalArgumentException("negative eval case " + id + " must not have keywords");
        }
        if (!negative && (keywords.isEmpty() || keywords.size() > 6)) {
            throw new IllegalArgumentException("eval case " + id + " needs 1-6 keyword groups");
        }
        for (List<String> group : keywords) {
            if (group.isEmpty() || group.stream().anyMatch(alias -> alias == null || alias.isBlank())) {
                throw new IllegalArgumentException("eval case " + id + " has a blank keyword alias");
            }
        }
        source = source == null || source.isBlank() ? "dragonball" : source;
        category = category == null ? "" : category;
        domain = domain == null ? "" : domain;
        sourceDocIds = sourceDocIds == null ? List.of() : List.copyOf(sourceDocIds);
        keypoints = keypoints == null ? List.of() : List.copyOf(keypoints);
    }

    public boolean inDataset(EvalDatasetSize size) {
        return tier <= size.size();
    }
}
