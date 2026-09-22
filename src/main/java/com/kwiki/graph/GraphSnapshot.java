package com.kwiki.graph;

/** 已注册图快照及其配套 Elasticsearch 版本身份。 */
public record GraphSnapshot(
        long snapshotId,
        long kbId,
        long graphVersion,
        long chunkIndexVersion,
        long communityIndexVersion,
        String chunkPhysicalIndex,
        String communityPhysicalIndex,
        String entityLinkingVersion,
        String sourceManifestHash,
        long contentEpoch,
        long securityEpoch) {

    /** 兼容仅携带 graphVersion 的领域调用方；持久化读取应使用带 snapshotId 的构造器。 */
    public GraphSnapshot(long kbId, long graphVersion, long chunkIndexVersion,
                         long communityIndexVersion, String chunkPhysicalIndex,
                         String communityPhysicalIndex, String entityLinkingVersion,
                         String sourceManifestHash, long contentEpoch, long securityEpoch) {
        this(graphVersion, kbId, graphVersion, chunkIndexVersion, communityIndexVersion,
                chunkPhysicalIndex, communityPhysicalIndex, entityLinkingVersion,
                sourceManifestHash, contentEpoch, securityEpoch);
    }

    public GraphSnapshot {
        if (snapshotId <= 0 || kbId <= 0 || graphVersion <= 0
                || chunkIndexVersion <= 0 || communityIndexVersion <= 0) {
            throw new IllegalArgumentException("图快照版本身份无效");
        }
        requireText(chunkPhysicalIndex, "chunkPhysicalIndex");
        requireText(communityPhysicalIndex, "communityPhysicalIndex");
        requireText(entityLinkingVersion, "entityLinkingVersion");
        requireText(sourceManifestHash, "sourceManifestHash");
        if (contentEpoch < 0 || securityEpoch < 0) {
            throw new IllegalArgumentException("图快照 epoch 无效");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
