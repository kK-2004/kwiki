package com.kwiki.graph;

/** 图批写的幂等结果。 */
public record GraphWriteResult(
        int sourceCount,
        int entityCount,
        int relationCount,
        int newRelationEvidenceCount,
        boolean alreadyApplied) {
}
