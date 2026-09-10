package com.kwiki.rag.retrieval;

import java.util.List;

/** 面向 CHILD 文档的一条召回分支；TopK 选取在 Elasticsearch 中完成。 */
public interface ChildRecallPort {

    enum Branch {BM25, VECTOR}

    /** 一次有效查询的分级子分块列表；无命中时为空。 */
    List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                          ScopeFilter scopeFilter, int topK);
}
