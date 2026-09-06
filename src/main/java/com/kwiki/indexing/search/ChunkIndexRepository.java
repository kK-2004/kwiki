package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.indexing.pipeline.IndexedVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Elasticsearch writer for chunk documents. Document ids are the stable chunk
 * keys (index = alias), so bulk writes are idempotent UPSERTs. Resource deletion
 * filters on resourceType AND resourceId, never deleting other resources' chunks.
 * Partial bulk failures are classified: transient statuses rethrow as retryable,
 * permanent rejections fail fast.
 */
@Component
public class ChunkIndexRepository implements ChunkIndexPort {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexRepository.class);

    private final ElasticsearchClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChunkIndexRepository(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
    }

    @Override
    public void upsertChunks(IndexedVersion version) {
        if (client == null) {
            return;
        }
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder().index(ElasticsearchIndexManager.ALIAS);
            for (int i = 0; i < version.parents().size(); i++) {
                Map<String, Object> document = ChunkDocument.parent(version.parents().get(i), version);
                bulk.operations(operation -> operation.index(index -> index
                        .id((String) document.get("chunkKey"))
                        .document(document)));
            }
            for (int i = 0; i < version.children().size(); i++) {
                Map<String, Object> document = ChunkDocument.child(
                        version.children().get(i), version.childVectors().get(i), version);
                bulk.operations(operation -> operation.index(index -> index
                        .id((String) document.get("chunkKey"))
                        .document(document)));
            }
            BulkResponse response = client.bulk(bulk.build());
            classify(response);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            throw new IllegalStateException("bulk indexing rejected", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bulk indexing failed", e);
        }
    }

    @Override
    public void deleteResourceChunks(String resourceType, long resourceId) {
        if (client == null) {
            return;
        }
        try {
            client.deleteByQuery(request -> request
                    .index(ElasticsearchIndexManager.ALIAS)
                    .refresh(true)
                    .query(query -> query.bool(bool -> bool
                            .filter(filter -> filter.term(
                                    term -> term.field("resourceType").value(resourceType)))
                            .filter(filter -> filter.term(
                                    term -> term.field("resourceId")
                                            .value(new FieldValue.Builder()
                                                    .longValue(resourceId).build()))))));
        } catch (Exception e) {
            throw new IllegalStateException("chunk deletion failed", e);
        }
    }

    private void classify(BulkResponse response) {
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                ErrorCause error = item.error();
                int status = item.status();
                String summary = "bulk item failed: " + error.type();
                if (status == 429 || status >= 500) {
                    throw new IllegalStateException("retryable: " + summary);
                }
                throw new IllegalStateException("permanent: " + summary);
            }
        }
    }
}
