package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ScopeFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BM25 branch: full-text match over CHILD documents with the scope filter applied
 * before TopK. Raw Elasticsearch scores are discarded — only the branch order
 * leaves this adapter.
 */
@Component
public class Bm25RecallAdapter implements ChildRecallPort {

    private final ElasticsearchClient client;

    public Bm25RecallAdapter(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
    }

    @Override
    public List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                                 ScopeFilter scopeFilter, int topK) {
        if (client == null) {
            return List.of();
        }
        try {
            SearchResponse<Map> response = client.search(request -> request
                            .index(ElasticsearchIndexManager.ALIAS)
                            .size(topK)
                            .query(query -> query.bool(bool -> bool
                                    .filter(filter -> filter.term(
                                            term -> term.field("chunkLevel").value("CHILD")))
                                    .filter(EsScopeFilterBuilder.build(scopeFilter))
                                    .must(must -> must.match(
                                            match -> match.field("content")
                                                    .query(effectiveQuery))))),
                    Map.class);
            return toHits(response.hits().hits());
        } catch (Exception e) {
            throw new IllegalStateException("bm25 recall failed", e);
        }
    }

    static List<ChunkHit> toHits(List<Hit<Map>> hits) {
        List<ChunkHit> mapped = new ArrayList<>();
        for (Hit<Map> hit : hits) {
            Map<?, ?> source = hit.source();
            if (source == null) {
                continue;
            }
            mapped.add(new ChunkHit(
                    text(source.get("chunkKey")),
                    text(source.get("parentChunkKey")),
                    number(source.get("kbId")),
                    text(source.get("resourceType")),
                    number(source.get("resourceId")),
                    source.get("revisionId") == null ? null : number(source.get("revisionId")),
                    text(source.get("headingPath")),
                    (int) number(source.get("charStart")),
                    (int) number(source.get("charEnd")),
                    text(source.get("content"))));
        }
        return mapped;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
