package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.kwiki.graph.CommunitySearchHit;
import com.kwiki.graph.CommunitySearchPort;
import com.kwiki.graph.CommunitySearchRequest;
import com.kwiki.graph.CommunitySearchResult;
import com.kwiki.graph.CommunityRrfFusion;
import com.kwiki.graph.GraphSourceRef;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** COMMUNITY 的 BM25/vector 双分支适配器，只接受请求已固定的物理索引。 */
@Component
public class CommunitySearchAdapter implements CommunitySearchPort {

    private final ElasticsearchClient client;

    public CommunitySearchAdapter(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
    }

    @Override
    public CommunitySearchResult search(CommunitySearchRequest request) {
        if (client == null) {
            return new CommunitySearchResult(List.of(), true, "community-search-unavailable");
        }
        if (!CommunityIndexNaming.isManaged(request.physicalIndex())) {
            throw new IllegalArgumentException("社区检索必须使用受控物理索引");
        }
        int top = Math.min(request.limit(), 10);
        CompletableFuture<List<CommunitySearchHit>> bm25 = CompletableFuture.supplyAsync(
                () -> bm25(request, top));
        CompletableFuture<List<CommunitySearchHit>> vector = request.queryVector() == null
                ? CompletableFuture.completedFuture(List.of())
                : CompletableFuture.supplyAsync(() -> vector(request, top));
        List<CommunitySearchHit> bm25Hits = joinOrEmpty(bm25);
        List<CommunitySearchHit> vectorHits = joinOrEmpty(vector);
        List<CommunityRrfFusion.RankedHit> bm25Ranked = rank(bm25Hits);
        List<CommunityRrfFusion.RankedHit> vectorRanked = rank(vectorHits);
        List<CommunitySearchHit> fused = CommunityRrfFusion.fuse(bm25Ranked, vectorRanked,
                Math.min(request.limit(), 3));
        boolean degraded = bm25.isCompletedExceptionally() || vector.isCompletedExceptionally();
        return new CommunitySearchResult(fused, degraded,
                degraded ? "community-search-branch-failed" : "");
    }

    private List<CommunitySearchHit> bm25(CommunitySearchRequest request, int top) {
        try {
            return map(client.search(search -> search.index(request.physicalIndex()).size(top)
                    .query(query -> query.multiMatch(match -> match
                            .query(request.query()).fields("title", "summary", "keywords"))),
                    Map.class).hits().hits(), request);
        } catch (Exception failure) {
            throw new IllegalStateException("community bm25 failed", failure);
        }
    }

    private List<CommunitySearchHit> vector(CommunitySearchRequest request, int top) {
        try {
            List<Float> values = new ArrayList<>();
            for (float value : request.queryVector()) values.add(value);
            return map(client.search(search -> search.index(request.physicalIndex()).size(top)
                    .knn(knn -> knn.field("vector").queryVector(values)
                            .numCandidates(top * 10).k(top)), Map.class)
                    .hits().hits(), request);
        } catch (Exception failure) {
            throw new IllegalStateException("community vector failed", failure);
        }
    }

    private static List<CommunitySearchHit> map(List<Hit<Map>> hits,
                                                CommunitySearchRequest request) {
        List<CommunitySearchHit> result = new ArrayList<>();
        for (Hit<Map> hit : hits) {
            Map<?, ?> source = hit.source();
            if (source == null || longValue(source.get("kbId")) != request.kbId()
                    || longValue(source.get("graphVersion")) != request.graphVersion()
                    || longValue(source.get("communityIndexVersion"))
                    != request.communityIndexVersion()) continue;
            result.add(new CommunitySearchHit(request.kbId(), request.graphVersion(),
                    request.communityIndexVersion(), text(source.get("communityId")),
                    text(source.get("title")), text(source.get("summary")),
                    strings(source.get("representativeEntityIds")),
                    sourceRefs(source.get("sourceRefs")), hit.score() == null ? 0 : hit.score()));
        }
        return result;
    }

    private static List<CommunityRrfFusion.RankedHit> rank(List<CommunitySearchHit> hits) {
        List<CommunityRrfFusion.RankedHit> result = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) result.add(new CommunityRrfFusion.RankedHit(hits.get(i), i + 1));
        return result;
    }

    private static List<CommunitySearchHit> joinOrEmpty(CompletableFuture<List<CommunitySearchHit>> future) {
        try { return future.join(); } catch (RuntimeException ignored) { return List.of(); }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .distinct().sorted().toList();
    }

    private static List<GraphSourceRef> sourceRefs(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<GraphSourceRef> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            try {
                result.add(new GraphSourceRef(text(map.get("sourceChunkId")),
                        (int) longValue(map.get("startOffset")),
                        (int) longValue(map.get("endOffset"))));
            } catch (RuntimeException ignored) {
                // 非法引用从在线辅助结果中丢弃，不能扩大可见来源。
            }
        }
        return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }
}
