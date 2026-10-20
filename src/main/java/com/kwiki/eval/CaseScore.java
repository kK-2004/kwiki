package com.kwiki.eval;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 单题各层命中的关键词组、丢失归因与上下文补回。 */
public record CaseScore(
        Map<EvalLayer, Set<Integer>> matched,
        int groupCount,
        Map<Integer, LossLayer> losses,
        Set<Integer> contextGains) {

    public CaseScore {
        Map<EvalLayer, Set<Integer>> matchedCopy = new EnumMap<>(EvalLayer.class);
        matched.forEach((layer, groups) ->
                matchedCopy.put(layer, Collections.unmodifiableSet(new TreeSet<>(groups))));
        matched = Collections.unmodifiableMap(matchedCopy);
        losses = Map.copyOf(losses);
        contextGains = Collections.unmodifiableSet(new TreeSet<>(contextGains));
    }

    public Set<Integer> matched(EvalLayer layer) {
        return matched.getOrDefault(layer, Set.of());
    }

    public double coverage(EvalLayer layer) {
        return groupCount == 0 ? 0.0 : matched(layer).size() / (double) groupCount;
    }

    public boolean fullHit(EvalLayer layer) {
        return groupCount > 0 && matched(layer).size() == groupCount;
    }
}
