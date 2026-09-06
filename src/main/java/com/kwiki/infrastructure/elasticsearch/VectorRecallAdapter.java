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
 * Vector branch: kNN search over CHILD document vectors with the same scope
 * filter applied before TopK. Raw similarity scores never leave the adapter.
 */
@Component
public class VectorRecallAdapter implements ChildRecallPort {

    private final ElasticsearchClient client;

    public VectorRecallAdapter(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
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
                                    .filter(EsScopeFilterBuilder.build(scopeFilter))),
                    Map.class)
                    .hits()
                    .hits();
            return Bm25RecallAdapter.toHits(hits);
        } catch (Exception e) {
            throw new IllegalStateException("vector recall failed", e);
        }
    }
}
