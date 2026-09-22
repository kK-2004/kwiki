package com.kwiki.graph;

/** 请求级快照 pin 门禁：固定配对版本和受控物理索引，禁止中途重新解析别名。 */
public final class GraphSnapshotPinGuard {

    private GraphSnapshotPinGuard() {
    }

    public static GraphSnapshotPin pin(GraphSnapshot snapshot, long leaseId,
                                       long selectedChunkIndexVersion) {
        if (snapshot == null || snapshot.chunkIndexVersion() != selectedChunkIndexVersion) {
            throw new IllegalStateException("请求选择的 Chunk 版本与图快照不匹配");
        }
        String expectedChunk = "kwiki-chunks-v" + snapshot.chunkIndexVersion();
        String expectedCommunity = "kwiki-communities-v" + snapshot.communityIndexVersion()
                + "-kb" + snapshot.kbId();
        if (!snapshot.chunkPhysicalIndex().equals(expectedChunk)
                || !snapshot.communityPhysicalIndex().equals(expectedCommunity)) {
            throw new IllegalStateException("图快照物理索引不受控");
        }
        return new GraphSnapshotPin(snapshot, leaseId);
    }
}
