package com.kwiki.graph;

import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 只接受固定快照代际下 READY 的 ES entityIds，最多取全请求 8 个种子。 */
public final class GraphSeedExtractor {

    public static final int MAX_SEEDS = 8;

    private GraphSeedExtractor() {
    }

    public static List<GraphSeed> extract(List<ChunkHit> hits, String expectedVersion) {
        Set<String> seen = new LinkedHashSet<>();
        List<GraphSeed> seeds = new ArrayList<>();
        if (hits == null || expectedVersion == null || expectedVersion.isBlank()) {
            return List.of();
        }
        for (ChunkHit hit : hits) {
            if (hit == null || hit.entityLinkingStatus() != EntityLinkingStatus.READY
                    || !expectedVersion.equals(hit.entityLinkingVersion())
                    || hit.sourceChunkId() == null || hit.sourceChunkId().isBlank()) {
                continue;
            }
            for (String entityId : hit.entityIds()) {
                if (seen.add(entityId)) {
                    seeds.add(new GraphSeed(entityId, hit.sourceChunkId()));
                    if (seeds.size() >= MAX_SEEDS) {
                        return List.copyOf(seeds);
                    }
                }
            }
        }
        return List.copyOf(seeds);
    }
}
