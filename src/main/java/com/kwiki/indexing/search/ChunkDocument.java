package com.kwiki.indexing.search;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.pipeline.IndexedVersion;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunk document shape for the versioned kwiki-chunks index. The document id is
 * the stable chunk key, so replays overwrite deterministically. Only CHILD
 * documents carry the dense vector.
 */
public final class ChunkDocument {

    public static final String LEVEL_PARENT = "PARENT";
    public static final String LEVEL_CHILD = "CHILD";

    private ChunkDocument() {
    }

    public static Map<String, Object> parent(ParentChunk chunk, IndexedVersion version) {
        Map<String, Object> document = commonFields(version);
        document.put("chunkLevel", LEVEL_PARENT);
        document.put("chunkKey", chunk.parentKey());
        document.put("parentChunkKey", null);
        document.put("parentOrdinal", chunk.parentOrdinal());
        document.put("headingPath", String.join(" > ", chunk.headingPath()));
        document.put("charStart", chunk.charStart());
        document.put("charEnd", chunk.charEnd());
        document.put("content", chunk.content());
        return document;
    }

    public static Map<String, Object> child(ChildChunk chunk, float[] vector,
                                            IndexedVersion version) {
        Map<String, Object> document = commonFields(version);
        document.put("chunkLevel", LEVEL_CHILD);
        document.put("chunkKey", chunk.childKey());
        document.put("parentChunkKey", chunk.parentKey());
        document.put("childOrdinal", chunk.childOrdinal());
        document.put("charStart", chunk.charStart());
        document.put("charEnd", chunk.charEnd());
        document.put("content", chunk.content());
        document.put("vector", vector);
        return document;
    }

    private static Map<String, Object> commonFields(IndexedVersion version) {
        Map<String, Object> document = new HashMap<>();
        document.put("resourceType", version.resourceType());
        document.put("resourceId", version.resourceId());
        document.put("revisionId", version.revisionId());
        document.put("kbId", version.kbId());
        document.put("parserVersion", version.parserVersion());
        document.put("chunkerVersion", version.chunkerVersion());
        document.put("embeddingModel", version.embeddingModel());
        document.put("indexVersion", version.indexVersion());
        return document;
    }
}
