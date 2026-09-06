package com.kwiki.indexing.pipeline;

/** Index-write boundary used by the indexing worker (implemented by the Elasticsearch adapter). */
public interface ChunkIndexPort {

    /** Idempotently writes the versioned parent/child chunk set. */
    void upsertChunks(IndexedVersion version);

    /** Removes every chunk of the resource from the active index. */
    void deleteResourceChunks(String resourceType, long resourceId);
}
