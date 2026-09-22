package com.kwiki.graph.persistence;

import java.util.List;

/** 与 run 绑定的不可变来源清单摘要；正文仍由权威业务存储提供。 */
public record GraphSourceManifest(long runId, long kbId, int chunkIndexVersion,
                                  String entityLinkingVersion, long contentEpoch,
                                  long securityEpoch, long eventWatermark,
                                  boolean contiguousEventWatermark,
                                  List<GraphSourceManifestEntry> entries,
                                  String manifestHash) {
    public GraphSourceManifest {
        if (runId <= 0 || kbId <= 0 || chunkIndexVersion < 1
                || entityLinkingVersion == null || entityLinkingVersion.isBlank()
                || contentEpoch < 0 || securityEpoch < 0 || eventWatermark < 0
                || manifestHash == null || manifestHash.isBlank()) {
            throw new IllegalArgumentException("来源清单身份无效");
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public boolean complete() {
        return contiguousEventWatermark && entries.stream()
                .allMatch(entry -> entityLinkingVersion.equals(entry.entityLinkingVersion())
                        && entry.readiness() == GraphSourceReadiness.READY);
    }
}
