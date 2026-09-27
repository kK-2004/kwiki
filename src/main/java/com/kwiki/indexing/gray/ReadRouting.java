package com.kwiki.indexing.gray;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 读路由快照：已切换灰度的「物理索引 → 知识库集合」；其余知识库读全局别名。 */
public record ReadRouting(Map<String, Set<Long>> kbIdsByGrayIndex) {

    public ReadRouting {
        kbIdsByGrayIndex = Map.copyOf(kbIdsByGrayIndex);
    }

    public static ReadRouting none() {
        return new ReadRouting(Map.of());
    }

    public boolean isEmpty() {
        return kbIdsByGrayIndex.isEmpty();
    }

    public Set<Long> switchedKbIds() {
        return kbIdsByGrayIndex.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }
}
