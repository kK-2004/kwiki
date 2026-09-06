package com.kwiki.rag.retrieval;

import java.util.List;

/** One recall branch over CHILD documents; TopK selection happens in Elasticsearch. */
public interface ChildRecallPort {

    enum Branch {BM25, VECTOR}

    /** Ranked children for one effective query; empty when nothing matches. */
    List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                          ScopeFilter scopeFilter, int topK);
}
