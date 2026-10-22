package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.kwiki.indexing.gray.GrayReadRoutes;
import com.kwiki.indexing.gray.ReadRouting;
import com.kwiki.rag.retrieval.RetrievalLifecycleService;
import com.kwiki.rag.retrieval.ScopeFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 检索与父块回取绝不把 dense_vector 带回：Top-50 命中的向量 JSON 约数 MB，
 * 经远程链路下载会耗时十秒级并触发分支超时（2026-10-22 评测 36/50 题失败的根因）。
 */
class EsVectorSourceExclusionTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class,
            withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final GrayReadRoutes routes = mock(GrayReadRoutes.class);
    private final ScopeFilter scope = new ScopeFilter(true, List.of(), List.of());

    @SuppressWarnings("unchecked")
    private ObjectProvider<ElasticsearchClient> provider() {
        ObjectProvider<ElasticsearchClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private static RetrievalLifecycleService lifecycle() {
        RetrievalLifecycleService lifecycle = new RetrievalLifecycleService(null, java.time.Duration.ofMinutes(5));
        lifecycle.pinForTest(new RetrievalLifecycleService.Exclusions(Set.of(), Set.of(), Instant.now()));
        return lifecycle;
    }

    private static SearchResponse<Map> empty() {
        return SearchResponse.of(b -> b.took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of())));
    }

    private SearchRequest capturedSearch() throws Exception {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(Map.class));
        return captor.getValue();
    }

    private static void assertExcludesVector(SearchRequest request) {
        assertThat(request.source()).isNotNull();
        assertThat(request.source().filter()).isNotNull();
        assertThat(request.source().filter().excludes()).contains("vector");
    }

    @Test
    void bm25RecallExcludesVector() throws Exception {
        when(routes.current()).thenReturn(ReadRouting.none());
        doReturn(empty()).when(client).search(any(SearchRequest.class), eq(Map.class));

        new Bm25RecallAdapter(provider(), lifecycle(), routes).search("问题", null, scope, 50);

        assertExcludesVector(capturedSearch());
    }

    @Test
    void vectorRecallExcludesVector() throws Exception {
        when(routes.current()).thenReturn(ReadRouting.none());
        doReturn(empty()).when(client).search(any(SearchRequest.class), eq(Map.class));

        new VectorRecallAdapter(provider(), lifecycle(), routes).search("问题", new float[]{0.1f, 0.2f}, scope, 50);

        assertExcludesVector(capturedSearch());
    }

    @Test
    void parentMultiGetExcludesVector() throws Exception {
        when(routes.current()).thenReturn(ReadRouting.none());
        MgetResponse<Map> response = MgetResponse.of(b -> b.docs(List.of()));
        doReturn(response).when(client).mget(any(MgetRequest.class), eq(Map.class));

        new EsParentChunkFetcher(provider(), lifecycle(), routes).fetchByKeys(List.of("p1"), scope);

        ArgumentCaptor<MgetRequest> captor = ArgumentCaptor.forClass(MgetRequest.class);
        verify(client).mget(captor.capture(), eq(Map.class));
        assertThat(captor.getValue().sourceExcludes()).contains("vector");
    }

    @Test
    void parentSearchUnderGrayRoutingExcludesVector() throws Exception {
        when(routes.current()).thenReturn(new ReadRouting(Map.of("kwiki-chunks-v4", Set.of(7L))));
        doReturn(empty()).when(client).search(any(SearchRequest.class), eq(Map.class));

        new EsParentChunkFetcher(provider(), lifecycle(), routes).fetchByKeys(List.of("p1"), scope);

        assertExcludesVector(capturedSearch());
    }
}
