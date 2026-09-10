package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;

import java.util.List;

/** 一个已完整处理、可用于幂等写索引的资源版本。 */
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
