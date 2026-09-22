package com.kwiki.graph;

/** 在社区搜索、摘要和任何图扩展前执行全来源覆盖与 epoch 门禁。 */
public final class GraphAuthorizationGate {

    public record Decision(boolean allowed, String reason) {
        public static Decision permit() { return new Decision(true, null); }
        public static Decision denied(String reason) { return new Decision(false, reason); }
    }

    public Decision check(GraphSourceCoverage coverage, GraphSnapshot snapshot,
                          long selectedChunkIndexVersion) {
        if (coverage == null || snapshot == null) {
            return Decision.denied("graph-snapshot-unavailable");
        }
        if (!coverage.complete()) {
            return Decision.denied("source-coverage-incomplete");
        }
        if (snapshot.chunkIndexVersion() != selectedChunkIndexVersion) {
            return Decision.denied("version-incompatible");
        }
        if (snapshot.contentEpoch() != coverage.currentContentEpoch()
                || snapshot.securityEpoch() != coverage.currentSecurityEpoch()) {
            return Decision.denied("source-epoch-stale");
        }
        return Decision.permit();
    }
}
