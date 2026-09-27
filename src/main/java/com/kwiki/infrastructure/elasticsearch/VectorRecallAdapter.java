package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ScopeFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final com.kwiki.indexing.gray.GrayReadRoutes routes;

    public VectorRecallAdapter(ObjectProvider<ElasticsearchClient> client,
                               com.kwiki.rag.retrieval.RetrievalLifecycleService lifecycle) {
        this(client, lifecycle, null);
    }

    /** 带灰度读路由：已切换灰度的知识库读取其灰度物理索引，其余仍经全局别名。 */
    @Autowired
    public VectorRecallAdapter(ObjectProvider<ElasticsearchClient> client,
                               com.kwiki.rag.retrieval.RetrievalLifecycleService lifecycle,
                               @org.springframework.lang.Nullable com.kwiki.indexing.gray.GrayReadRoutes routes) {
        this.client = client.getIfAvailable();
        this.lifecycle = lifecycle;
        this.routes = routes;
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
            // 无灰度路由时 indices 仅为 ElasticsearchIndexManager.ALIAS，且不追加过滤
            com.kwiki.indexing.gray.ReadRouting routing = routes == null
                    ? com.kwiki.indexing.gray.ReadRouting.none() : routes.current();
            co.elastic.clients.elasticsearch._types.query_dsl.Query scope = EsScopeFilterBuilder.build(
                    scopeFilter, lifecycle.exclusions());
            co.elastic.clients.elasticsearch._types.query_dsl.Query knnFilter = EsReadRouting.filter(routing)
                    .map(route -> co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q.bool(b -> b
                            .filter(scope).filter(route))))
                    .orElse(scope);
            // 灰度物理索引缺失时忽略该索引，不拖垮全部检索；无灰度路由时保持原请求
            List<Hit<Map>> hits = client.search(request -> request
                            .index(EsReadRouting.indices(routing))
                            .ignoreUnavailable(routing.isEmpty() ? null : Boolean.TRUE)
                            .size(topK)
                            .knn(knn -> knn
                                    .field("vector")
                                    .queryVector(vector)
                                    .numCandidates(topK * 10)
                                    .k(topK)
                                    .filter(knnFilter)),
                    Map.class)
                    .hits()
                    .hits();
            return Bm25RecallAdapter.toHits(hits);
        } catch (Exception e) {
            throw new IllegalStateException("vector recall failed", e);
        }
    }
}
