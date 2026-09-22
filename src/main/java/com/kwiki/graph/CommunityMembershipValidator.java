package com.kwiki.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 校验 Leiden 输出覆盖每个输入实体恰好一次；空图是合法结果。 */
public final class CommunityMembershipValidator {

    private CommunityMembershipValidator() {
    }

    public static void requireComplete(List<String> inputEntityIds,
                                       List<CommunityMembership> memberships,
                                       long kbId, long graphVersion) {
        Set<String> expected = new HashSet<>(inputEntityIds == null ? List.of() : inputEntityIds);
        HashMap<String, Integer> counts = new HashMap<>();
        for (CommunityMembership membership : memberships == null ? List.<CommunityMembership>of() : memberships) {
            if (membership.kbId() != kbId || membership.graphVersion() != graphVersion
                    || !expected.contains(membership.entityId())) {
                throw new IllegalArgumentException("社区结果包含越界实体或版本");
            }
            counts.merge(membership.entityId(), 1, Integer::sum);
        }
        if (counts.size() != expected.size()
                || counts.values().stream().anyMatch(count -> count != 1)) {
            throw new IllegalArgumentException("社区结果未覆盖每个输入实体恰好一次");
        }
    }
}
