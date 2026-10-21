package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.RefreshRequest;
import co.elastic.clients.elasticsearch.indices.RefreshResponse;
import com.kwiki.graph.CommunityIndexWriteRequest;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.UsageSteps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 社区索引写入的用量归属：embedding 必须在「索引向量」作用域内调用，
 * 否则社区摘要的向量会混进查询向量的口径里。
 */
class CommunityIndexWriterUsageTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);

    /** embedding 调用时观察到的作用域步骤。 */
    final List<String> observedSteps = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        when(client.indices()).thenReturn(indices);
        doReturn(RefreshResponse.of(b -> b.shards(s -> s.total(1).successful(1).failed(0))))
                .when(indices).refresh(org.mockito.ArgumentMatchers
                        .<java.util.function.Function<RefreshRequest.Builder,
                                co.elastic.clients.util.ObjectBuilder<RefreshRequest>>>any());
        doReturn(bulkResponse()).when(client).bulk(any(BulkRequest.class));
        doReturn(searchResponse()).when(client).search(org.mockito.ArgumentMatchers
                .<java.util.function.Function<SearchRequest.Builder,
                        co.elastic.clients.util.ObjectBuilder<SearchRequest>>>any(), eq(Map.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return (ObjectProvider<T>) new ObjectProvider<Object>() {
            @Override
            public Object getIfAvailable() {
                return value;
            }
        };
    }

    /** 记录调用时刻的用量作用域步骤，并返回维度匹配的向量。 */
    private final ChunkEmbeddingPort embeddings = texts -> {
        observedSteps.add(ModelUsageContext.resolve().step());
        return texts.stream().map(text -> new float[]{0.1f, 0.2f}).toList();
    };

    @Test
    void 社区索引写入的向量记为索引向量() throws Exception {
        CommunityIndexWriter writer = new CommunityIndexWriter(provider(client), provider(embeddings),
                provider(null), provider(null));

        writer.write(List.of(request()));

        assertThat(observedSteps).containsExactly(UsageSteps.INDEX_EMBEDDING);
    }

    @Test
    void 写入结束后索引向量作用域被释放() throws Exception {
        CommunityIndexWriter writer = new CommunityIndexWriter(provider(client), provider(embeddings),
                provider(null), provider(null));

        writer.write(List.of(request()));

        assertThat(ModelUsageContext.resolve().step()).isNull();
    }

    private static CommunityIndexWriteRequest request() {
        CommunitySummary summary = new CommunitySummary("社区标题", "社区摘要正文",
                List.of("关键词"), List.of("核心概念"), List.of(), List.of(), List.of());
        return new CommunityIndexWriteRequest("kwiki-communities-v1-kb1", 1L, 1L, 1L,
                "c1", "manifest-1", 0L, 0L, 1, List.of("e1"), 1,
                "summary-model", "prompt-v1", "text-embedding-v4", 2, summary);
    }

    private static BulkResponse bulkResponse() {
        BulkResponseItem item = BulkResponseItem.of(builder -> {
            builder.operationType(co.elastic.clients.elasticsearch.core.bulk.OperationType.Index);
            builder.index("kwiki-communities-v1-kb1").id("kb_1:1:c1");
            builder.status(200);
            return builder;
        });
        return BulkResponse.of(builder -> builder.took(1).errors(false).items(List.of(item)));
    }

    @SuppressWarnings("unchecked")
    private static SearchResponse<Map> searchResponse() {
        return SearchResponse.of(b -> {
            b.took(1).timedOut(false)
                    .shards(s -> s.total(1).successful(1).failed(0))
                    .hits(h -> h.hits(hit -> hit
                            .index("kwiki-communities-v1-kb1")
                            .id("kb_1:1:c1")
                            .source(Map.of("communityKey", "kb_1:1:c1"))));
            return b;
        });
    }
}
