package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.pipeline.IndexedVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk-write contract with a mocked client: deterministic ids (= chunk keys),
 * writes target the alias, resource deletes filter on type AND id, and bulk
 * failures classify as transient or permanent. Document shapes are covered by
 * the ChunkDocument tests.
 */
@ExtendWith(MockitoExtension.class)
class ChunkIndexRepositoryTest {

    @Mock
    ElasticsearchClient client;

    private ChunkIndexRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChunkIndexRepository(provider(client));
    }

    private static ObjectProvider<ElasticsearchClient> provider(ElasticsearchClient value) {
        return new ObjectProvider<>() {
            @Override
            public ElasticsearchClient getIfAvailable() {
                return value;
            }
        };
    }

    private final ParentChunk parentChunk = new ParentChunk(
            "PAGE:7:103:P0", 0, List.of("标题"), 0, 100, "内容",
            ParentChunk.BoundaryType.HEADING);

    private final ChildChunk childChunk = new ChildChunk(
            "PAGE:7:103:P0:C0", 0, "PAGE:7:103:P0", 10, 90, "片段",
            ChildChunk.BoundaryType.PARAGRAPH);

    private final IndexedVersion version = new IndexedVersion(
            "PAGE", 7L, 103L, 1L, "parser-1", "chunker-1", "text-embedding-v4", 1,
            List.of(parentChunk), List.of(childChunk), List.of(new float[]{0.1f, 0.2f}));

    @Test
    void upsertUsesChunkKeysAsDeterministicIdsAgainstTheAlias() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(null));

        repository.upsertChunks(version);

        ArgumentCaptor<BulkRequest> request = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(request.capture());
        BulkRequest bulk = request.getValue();
        assertThat(bulk.index()).isEqualTo("kwiki-chunks");
        assertThat(bulk.operations()).hasSize(2);
        assertThat(bulk.operations().get(0).index().id()).isEqualTo("PAGE:7:103:P0");
        assertThat(bulk.operations().get(1).index().id()).isEqualTo("PAGE:7:103:P0:C0");
    }

    @Test
    void transientBulkItemFailureIsRetryable() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(429));

        assertThatThrownBy(() -> repository.upsertChunks(version))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retryable");
    }

    @Test
    void permanentBulkItemFailureFailsFast() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(400));

        assertThatThrownBy(() -> repository.upsertChunks(version))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permanent");
    }

    @Test
    void deleteFiltersOnBothResourceTypeAndId() throws Exception {
        java.util.concurrent.atomic.AtomicReference<DeleteByQueryRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        // repository calls the fluent Function overload: apply it ourselves and capture
        when(client.deleteByQuery(org.mockito.ArgumentMatchers
                .<java.util.function.Function<DeleteByQueryRequest.Builder,
                        co.elastic.clients.util.ObjectBuilder<DeleteByQueryRequest>>>any()))
                .thenAnswer(invocation -> {
                    var builder = invocation.<java.util.function.Function<
                            DeleteByQueryRequest.Builder,
                            co.elastic.clients.util.ObjectBuilder<DeleteByQueryRequest>>>getArgument(0);
                    captured.set(builder.apply(new DeleteByQueryRequest.Builder()).build());
                    return null;
                });

        assertThatCode(() -> repository.deleteResourceChunks("PAGE", 7L))
                .doesNotThrowAnyException();

        List<String> fields = captured.get().query().bool().filter().stream()
                .map(filter -> filter.term().field())
                .toList();
        assertThat(captured.get().allowNoIndices()).isTrue();
        assertThat(captured.get().ignoreUnavailable()).isTrue();
        assertThat(fields).containsExactlyInAnyOrder("resourceType", "resourceId");
        boolean idFilterIsSeven = captured.get().query().bool().filter().stream()
                .anyMatch(filter -> "resourceId".equals(filter.term().field())
                        && filter.term().value().isLong()
                        && filter.term().value().longValue() == 7L);
        assertThat(idFilterIsSeven).as("deletes must be scoped to one resource").isTrue();
    }

    @Test
    void chunkDocumentShapesMatchLevels() {
        Map<String, Object> parent = ChunkDocument.parent(parentChunk, version);
        Map<String, Object> child = ChunkDocument.child(childChunk, version.childVectors().get(0), version);

        assertThat(parent.get("chunkLevel")).hasToString("PARENT");
        assertThat(parent).doesNotContainKey("vector");
        assertThat(parent.get("headingPath")).hasToString("标题");
        assertThat(parent.get("parserVersion")).hasToString("parser-1");

        assertThat(child.get("chunkLevel")).hasToString("CHILD");
        assertThat(child).containsKey("vector");
        assertThat(child.get("parentChunkKey")).hasToString("PAGE:7:103:P0");
        assertThat(child.get("embeddingModel")).hasToString("text-embedding-v4");
    }

    private static BulkResponse bulkResponse(Integer errorStatus) {
        BulkResponseItem item = BulkResponseItem.of(builder -> {
            builder.operationType(co.elastic.clients.elasticsearch.core.bulk.OperationType.Index);
            builder.index("kwiki-chunks-v1").id("x");
            builder.status(errorStatus == null ? 200 : errorStatus);
            if (errorStatus != null) {
                builder.error(ErrorCause.of(error -> error.type("bulk_failure").reason("simulated")));
            }
            return builder;
        });
        return BulkResponse.of(builder -> builder
                .took(5).errors(errorStatus != null).items(List.of(item)));
    }
}
