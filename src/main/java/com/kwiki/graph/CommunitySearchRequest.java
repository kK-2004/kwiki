package com.kwiki.graph;

/** 固定社区快照上的受控检索请求。 */
public record CommunitySearchRequest(
        long kbId,
        long graphVersion,
        long communityIndexVersion,
        String physicalIndex,
        String query,
        float[] queryVector,
        int limit) {

    public CommunitySearchRequest {
        if (kbId <= 0 || graphVersion <= 0 || communityIndexVersion <= 0
                || physicalIndex == null || physicalIndex.isBlank()
                || query == null || query.isBlank() || limit <= 0) {
            throw new IllegalArgumentException("社区检索请求无效");
        }
        queryVector = queryVector == null ? null : queryVector.clone();
    }

    @Override
    public float[] queryVector() {
        return queryVector == null ? null : queryVector.clone();
    }
}
