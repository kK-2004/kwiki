package com.kwiki.graph;

import java.util.Optional;

/** 图快照注册和按 Chunk 版本固定配对的端口。 */
public interface GraphSnapshotRegistry {

    GraphSnapshot register(GraphSnapshot snapshot);

    Optional<GraphSnapshot> findPublished(long kbId, long chunkIndexVersion);
}
