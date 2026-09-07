package com.kwiki.indexing.pipeline;

import java.util.List;

/** Embedding boundary used by the indexing worker (implemented by the Qwen adapter). */
public interface ChunkEmbeddingPort {

    default String cacheIdentity() {
        return getClass().getName();
    }

    /**
     * Embeds texts in order; result size must equal input size and match the configured dimension.
     */
    List<float[]> embed(List<String> texts);
}
