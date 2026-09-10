package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ScopeFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 向量分支：在 CHILD 文档向量上进行 kNN 搜索，相同的范围
 * 过滤器（scope filter）在 TopK 之前应用。原始的相似度评分绝不离开此适配器（adapter）。
 */
@Component
public class VectorRecallAdapter implements ChildRecallPort {

    private final ElasticsearchClient client;
    private final com.kwiki.rag.retrieval.RetrievalLifecycleService lifecycle;

    public VectorRecallAdapter(ObjectProvider<ElasticsearchClient> client,
                               com.kwiki.rag.retrieval.RetrievalLifecycleService lifecycle) {
        this.client = client.getIfAvailable();
        this.lifecycle = lifecycle;
    }

    @Override
    public List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                                 ScopeFilter scopeFilter, int topK) {
        if (client == null || queryVector == null) {
            return List.of();
        }
        try {
            List<Float> vector = new java.util.ArrayList<>(queryVector.length);
            for (float value : queryVector) {
                vector.add(value);
            }
            List<Hit<Map>> hits = client.search(request -> request
                            .index(ElasticsearchIndexManager.ALIAS)
                            .size(topK)
                            .knn(knn -> knn
                                    .field("vector")
                                    .queryVector(vector)
                                    .numCandidates(topK * 10)
                                    .k(topK)
                                    .filter(EsScopeFilterBuilder.build(
                                            scopeFilter, lifecycle.exclusions()))),
                    Map.class)
                    .hits()
                    .hits();
            return Bm25RecallAdapter.toHits(hits);
        } catch (Exception e) {
            throw new IllegalStateException("vector recall failed", e);
        }
    }
}
