package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.kwiki.graph.GraphSourceChild;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.rag.retrieval.ChunkHit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 按知识库列举 CHILD 来源块：分页拼接、排序与容量上限。 */
class EsGraphSourceChunkListingTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class,
            withSettings().defaultAnswer(CALLS_REAL_METHODS));

    @SuppressWarnings("unchecked")
    private EsGraphSourceChunkListing listing() {
        ObjectProvider<ElasticsearchClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new EsGraphSourceChunkListing(provider);
    }

    private static Map<String, Object> child(String key, long kbId) {
        String content = "content-" + key;
        // 与索引写入一致：按完整元组计算稳定来源身份
        String sourceChunkId = new GraphSourceChunk(kbId, "PAGE", 1L, 3L, 2L, 5,
                "parser-1", "chunker-1", key, sha256(content)).sourceChunkId();
        return Map.ofEntries(
                Map.entry("chunkKey", key), Map.entry("chunkLevel", "CHILD"),
                Map.entry("kbId", kbId), Map.entry("resourceType", "PAGE"),
                Map.entry("resourceId", 1L), Map.entry("revisionId", 3L),
                Map.entry("lifecycleVersion", 2L), Map.entry("parserVersion", "parser-1"),
                Map.entry("chunkerVersion", "chunker-1"), Map.entry("headingPath", "h"),
                Map.entry("charStart", 0), Map.entry("charEnd", 5),
                Map.entry("content", content),
                Map.entry("sourceChunkId", sourceChunkId));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static SearchResponse<Map> response(int total, List<Map<String, Object>> sources) {
        return SearchResponse.of(b -> {
            b.took(1).timedOut(false)
                    .shards(s -> s.total(1).successful(1).failed(0))
                    .hits(h -> {
                        h.total(t -> t.value(total)
                                .relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq));
                        sources.forEach(source -> h.hits(hit -> hit
                                .index("kwiki-chunks-v5")
                                .id(String.valueOf(source.get("chunkKey")))
                                .source(source)
                                .sort(List.of(FieldValue.of(String.valueOf(source.get("chunkKey")))))));
                        return h;
                    });
            return b;
        });
    }

    @Test
    void 按search_after分页拼接并按chunkKey排序() throws Exception {
        List<Map<String, Object>> page1 = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            page1.add(child(String.format("kb7:page:1:chunk:%03d", i), 7L));
        }
        List<Map<String, Object>> page2 = List.of(
                child("kb7:page:2:chunk:000", 7L),
                child("kb7:page:2:chunk:001", 7L),
                child("kb7:page:2:chunk:002", 7L));
        doReturn(response(503, page1)).doReturn(response(503, page2))
                .when(client).search(any(SearchRequest.class), eq(Map.class));

        List<GraphSourceChild> children = listing().listChildren("kwiki-chunks-v5", 7L, 1000);

        assertThat(children).hasSize(503);
        assertThat(children).isSortedAccordingTo(Comparator.comparing(child -> child.hit().chunkKey()));
        assertThat(children.get(0).hit().chunkKey()).isEqualTo("kb7:page:1:chunk:000");
        assertThat(children.get(0).hit().entityLinkingStatus()).isNotNull();
        // 还原的稳定身份与 CHILD 文档写入的 sourceChunkId 一致（同一元组计算）
        GraphSourceChild first = children.get(0);
        assertThat(first.toSourceChunk(5).sourceChunkId()).isEqualTo(first.hit().sourceChunkId());

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client, times(2)).search(captor.capture(), eq(Map.class));
        SearchRequest firstRequest = captor.getAllValues().get(0);
        SearchRequest secondRequest = captor.getAllValues().get(1);
        assertThat(firstRequest.index()).containsExactly("kwiki-chunks-v5");
        assertThat(firstRequest.searchAfter()).isEmpty();
        assertThat(secondRequest.searchAfter()).hasSize(1);
        assertThat(secondRequest.searchAfter().get(0).stringValue())
                .isEqualTo(page1.get(499).get("chunkKey"));
    }

    @Test
    void 来源块总数超过上限时永久失败() throws Exception {
        doReturn(response(501, List.of(child("kb7:page:1:chunk:000", 7L))))
                .when(client).search(any(SearchRequest.class), eq(Map.class));

        GraphBuildFailure failure = catchThrowableOfType(
                () -> listing().listChildren("kwiki-chunks-v5", 7L, 500), GraphBuildFailure.class);

        assertThat(failure.code()).isEqualTo("CAPACITY_EXCEEDED_SOURCES");
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.getMessage()).contains("500");
        verify(client, times(1)).search(any(SearchRequest.class), eq(Map.class));
    }

    @Test
    void 恰好等于上限时不失败() throws Exception {
        List<Map<String, Object>> page1 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            page1.add(child(String.format("kb7:page:1:chunk:%03d", i), 7L));
        }
        doReturn(response(500, page1)).when(client).search(any(SearchRequest.class), eq(Map.class));

        List<GraphSourceChild> children = listing().listChildren("kwiki-chunks-v5", 7L, 500);

        assertThat(children).hasSize(5);
    }

    @Test
    void 客户端缺失时按可重试失败处理() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ElasticsearchClient> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        EsGraphSourceChunkListing listing = new EsGraphSourceChunkListing(empty);

        GraphBuildFailure failure = catchThrowableOfType(
                () -> listing.listChildren("kwiki-chunks-v5", 7L, 500), GraphBuildFailure.class);

        assertThat(failure.retryable()).isTrue();
    }
}
