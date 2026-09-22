package com.kwiki.graph;

/** 社区发现输入及固定算法参数。 */
public record CommunityDetectionInput(
        long kbId,
        long graphVersion,
        GraphAlgorithmMode algorithmMode,
        int maxIterations,
        double resolution,
        String projectionHash,
        String runId,
        long fencingToken) {

    public CommunityDetectionInput {
        if (kbId <= 0 || graphVersion <= 0 || algorithmMode == null
                || maxIterations <= 0 || resolution <= 0
                || projectionHash == null || projectionHash.isBlank()
                || runId == null || runId.isBlank() || fencingToken < 0) {
            throw new IllegalArgumentException("社区发现输入无效");
        }
    }
}
