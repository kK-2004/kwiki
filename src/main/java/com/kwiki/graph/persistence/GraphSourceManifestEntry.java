package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;

/** 持久化来源清单的一行；状态不会通过跳过来源伪装为完整。 */
public record GraphSourceManifestEntry(GraphSourceChunk sourceChunk,
                                       String entityLinkingVersion,
                                       GraphSourceReadiness readiness) {
    public GraphSourceManifestEntry {
        if (sourceChunk == null || entityLinkingVersion == null || entityLinkingVersion.isBlank()
                || readiness == null) {
            throw new IllegalArgumentException("来源清单项无效");
        }
    }
}
