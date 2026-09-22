package com.kwiki.graph;

/** 一个实体在指定图快照中的唯一社区归属。 */
public record CommunityMembership(
        long kbId,
        long graphVersion,
        String entityId,
        String communityId) {

    public CommunityMembership {
        if (kbId <= 0 || graphVersion <= 0 || entityId == null || entityId.isBlank()
                || communityId == null || communityId.isBlank()) {
            throw new IllegalArgumentException("社区成员身份无效");
        }
    }
}
