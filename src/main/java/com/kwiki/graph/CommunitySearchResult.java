package com.kwiki.graph;

import java.util.List;

/** 社区搜索分支结果，区分零命中与服务失败。 */
public record CommunitySearchResult(
        List<CommunitySearchHit> hits,
        boolean degraded,
        String degradationReason) {

    public CommunitySearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        degradationReason = degradationReason == null ? "" : degradationReason;
    }
}
