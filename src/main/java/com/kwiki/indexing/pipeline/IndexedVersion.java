package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;

import java.util.List;

/** One fully processed resource version ready for idempotent index writes. */
public record IndexedVersion(
        String resourceType,
        long resourceId,
        Long revisionId,
        long kbId,
        String parserVersion,
        String chunkerVersion,
        String embeddingModel,
        int indexVersion,
        List<ParentChunk> parents,
        List<ChildChunk> children,
        List<float[]> childVectors) {
}
