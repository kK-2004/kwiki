package com.kwiki.graph;

import java.util.List;

/** 与具体算法引擎输出格式无关的社区成员结果。 */
public record CommunityDetectionResult(
        GraphAlgorithmMode algorithmMode,
        String engineVersion,
        List<CommunityMembership> memberships) {

    public CommunityDetectionResult {
        if (algorithmMode == null || engineVersion == null || engineVersion.isBlank()) {
            throw new IllegalArgumentException("社区发现结果身份无效");
        }
        memberships = memberships == null ? List.of() : List.copyOf(memberships);
    }
}
