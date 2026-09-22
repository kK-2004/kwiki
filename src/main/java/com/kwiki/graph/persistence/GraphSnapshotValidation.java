package com.kwiki.graph.persistence;

import com.kwiki.rag.retrieval.EntityLinkingStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 发布前验证来源覆盖、ES entityIds 与同代抽取结果的一致性。 */
public final class GraphSnapshotValidation {

    public record Report(boolean valid, long checkedSources, long readySources,
                         String failureReason) {}

    private GraphSnapshotValidation() {
    }

    public static Report check(List<GraphSourceManifestEntry> manifest,
                               List<EntityMappingObservation> observed,
                               String expectedVersion,
                               Map<String, List<String>> expectedEntityIds) {
        if (manifest == null || expectedVersion == null || expectedVersion.isBlank()) {
            return new Report(false, 0, 0, "manifest-or-version-missing");
        }
        Map<String, EntityMappingObservation> bySource = new HashMap<>();
        for (EntityMappingObservation item : observed == null
                ? List.<EntityMappingObservation>of() : observed) {
            bySource.put(item.sourceChunkId(), item);
        }
        long ready = 0;
        for (GraphSourceManifestEntry entry : manifest) {
            if (entry.readiness() != GraphSourceReadiness.READY) {
                return new Report(false, manifest == null ? 0 : manifest.size(), ready,
                        "source-not-ready");
            }
            EntityMappingObservation item = bySource.get(entry.sourceChunk().sourceChunkId());
            if (item == null || item.status() != EntityLinkingStatus.READY
                    || !expectedVersion.equals(item.entityLinkingVersion())) {
                return new Report(false, manifest.size(), ready, "entity-mapping-not-ready");
            }
            List<String> expected = expectedEntityIds == null
                    ? List.of() : expectedEntityIds.getOrDefault(item.sourceChunkId(), List.of());
            if (!expected.stream().distinct().sorted().toList().equals(item.entityIds())) {
                return new Report(false, manifest.size(), ready, "entity-mapping-mismatch");
            }
            ready++;
        }
        return new Report(true, manifest.size(), ready, null);
    }
}
