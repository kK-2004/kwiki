package com.kwiki.graph;

/** 社区的完整稳定身份，禁止在跨请求数据中使用裸 communityId。 */
public record GraphCommunityIdentity(long kbId, long graphVersion, String communityId) {

    public GraphCommunityIdentity {
        if (kbId <= 0 || graphVersion <= 0 || communityId == null || communityId.isBlank()) {
            throw new IllegalArgumentException("社区完整身份无效");
        }
    }

    public String value() {
        return kbId + ":" + graphVersion + ":" + communityId;
    }
}
