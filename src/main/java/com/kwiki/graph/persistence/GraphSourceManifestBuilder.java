package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在一致性读结果上构建确定性的分页来源清单。 */
public final class GraphSourceManifestBuilder {

    public GraphSourceManifest build(long runId, long kbId, int chunkIndexVersion,
                                     String entityLinkingVersion, long contentEpoch,
                                     long securityEpoch, long eventWatermark,
                                     boolean contiguousEventWatermark,
                                     List<GraphSourceChunk> sources,
                                     Map<String, GraphSourceReadiness> readiness) {
        List<GraphSourceManifestEntry> entries = (sources == null ? List.<GraphSourceChunk>of() : sources)
                .stream().filter(source -> source != null)
                .sorted(Comparator.comparing(GraphSourceChunk::sourceChunkId))
                .map(source -> {
                    if (source.kbId() != kbId || source.indexVersion() != chunkIndexVersion) {
                        throw new IllegalArgumentException("来源清单包含错误知识库或 Chunk 版本");
                    }
                    GraphSourceReadiness state = readiness == null
                            ? GraphSourceReadiness.WAITING_FOR_CHUNKS
                            : readiness.getOrDefault(source.sourceChunkId(),
                                    GraphSourceReadiness.WAITING_FOR_CHUNKS);
                    return new GraphSourceManifestEntry(source, entityLinkingVersion, state);
                }).toList();
        String hash = hash(kbId, chunkIndexVersion, entityLinkingVersion, contentEpoch,
                securityEpoch, eventWatermark, entries);
        return new GraphSourceManifest(runId, kbId, chunkIndexVersion, entityLinkingVersion,
                contentEpoch, securityEpoch, eventWatermark, contiguousEventWatermark,
                entries, hash);
    }

    private static String hash(long kbId, int chunkIndexVersion, String entityLinkingVersion,
                               long contentEpoch, long securityEpoch, long eventWatermark,
                               List<GraphSourceManifestEntry> entries) {
        StringBuilder canonical = new StringBuilder()
                .append(kbId).append('|').append(chunkIndexVersion).append('|')
                .append(entityLinkingVersion).append('|').append(contentEpoch).append('|')
                .append(securityEpoch).append('|').append(eventWatermark);
        for (GraphSourceManifestEntry entry : entries) {
            GraphSourceChunk source = entry.sourceChunk();
            canonical.append('|').append(source.sourceChunkId()).append(':')
                    .append(entry.readiness());
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行时缺少 SHA-256", failure);
        }
    }
}
