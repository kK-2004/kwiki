package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.kwiki.indexing.gray.GrayReadRoutes;
import com.kwiki.indexing.gray.ReadRouting;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class EsParentChunkFetcherTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class,
            withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final GrayReadRoutes routes = mock(GrayReadRoutes.class);
    private final ScopeFilter superuser = new ScopeFilter(true, List.of(), List.of());

    @SuppressWarnings("unchecked")
    private EsParentChunkFetcher fetcher() {
        ObjectProvider<ElasticsearchClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        RetrievalLifecycleService lifecycle = new RetrievalLifecycleService(null, java.time.Duration.ofMinutes(5));
        lifecycle.pinForTest(new RetrievalLifecycleService.Exclusions(Set.of(), Set.of(), Instant.now()));
        return new EsParentChunkFetcher(provider, lifecycle, routes);
    }

    private static Map<String, Object> parent(String key, long kbId) {
        return Map.of("chunkKey", key, "kbId", kbId, "resourceType", "PAGE", "resourceId", 1L,
                "headingPath", "h", "content", "c-" + key);
    }

    @Test
    void 无灰度路由时仍在别名上_multiGet() throws Exception {
        when(routes.current()).thenReturn(ReadRouting.none());
        MgetResponse<Map> response = MgetResponse.of(b -> b.docs(d -> d.result(r -> r
                .index("kwiki-chunks-v1").id("p1").found(true).source(parent("p1", 7L)))));
        doReturn(response).when(client).mget(any(MgetRequest.class), eq(Map.class));

        List<ParentEvidenceChunk> parents = fetcher().fetchByKeys(List.of("p1"), superuser);

        ArgumentCaptor<MgetRequest> captor = ArgumentCaptor.forClass(MgetRequest.class);
        verify(client).mget(captor.capture(), eq(Map.class));
        assertThat(captor.getValue().index()).isEqualTo("kwiki-chunks");
        assertThat(captor.getValue().ids()).containsExactly("p1");
        verify(client, never()).search(any(SearchRequest.class), eq(Map.class));
        assertThat(parents).extracting(ParentEvidenceChunk::parentChunkKey).containsExactly("p1");
    }

    @Test
    void 有灰度路由时在别名与灰度索引上按_id_搜索并附加路由过滤() throws Exception {
        ReadRouting routing = new ReadRouting(Map.of("kwiki-chunks-v4", Set.of(7L)));
        when(routes.current()).thenReturn(routing);
        // 命中顺序与请求键顺序相反，验证结果按请求键顺序返回
        SearchResponse<Map> response = SearchResponse.of(b -> b
                .took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h
                        .hits(hit -> hit.index("kwiki-chunks-v1").id("p2").source(parent("p2", 8L)))
                        .hits(hit -> hit.index("kwiki-chunks-v4").id("p1").source(parent("p1", 7L)))));
        doReturn(response).when(client).search(any(SearchRequest.class), eq(Map.class));

        List<ParentEvidenceChunk> parents = fetcher().fetchByKeys(List.of("p1", "p2"), superuser);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(Map.class));
        verify(client, never()).mget(any(MgetRequest.class), eq(Map.class));
        SearchRequest request = captor.getValue();
        assertThat(request.index()).containsExactly("kwiki-chunks", "kwiki-chunks-v4");
        assertThat(request.size()).isEqualTo(2);
        // 灰度物理索引缺失时不应拖垮全部检索
        assertThat(request.ignoreUnavailable()).isTrue();
        var filters = request.query().bool().filter();
        assertThat(filters).hasSize(2);
        assertThat(filters.get(0).ids().values()).containsExactly("p1", "p2");
        assertThat(filters.get(1)).hasToString(EsReadRouting.filter(routing).orElseThrow().toString());
        assertThat(parents).extracting(ParentEvidenceChunk::parentChunkKey).containsExactly("p1", "p2");
        assertThat(parents.get(0).content()).isEqualTo("c-p1");
    }
}
