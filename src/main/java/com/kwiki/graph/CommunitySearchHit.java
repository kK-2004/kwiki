package com.kwiki.graph;

import java.util.List;

/** 社区检索命中及其固定版本身份。 */
public record CommunitySearchHit(
        long kbId,
        long graphVersion,
        long communityIndexVersion,
        String communityId,
        String title,
        String summary,
        List<String> representativeEntityIds,
        List<GraphSourceRef> sourceRefs,
        double score) {

    public CommunitySearchHit {
        representativeEntityIds = representativeEntityIds == null ? List.of() : List.copyOf(representativeEntityIds);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
