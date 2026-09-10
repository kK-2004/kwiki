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
                    .allowNoIndices(true)
                    .ignoreUnavailable(true)
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

    /** Result of a checked deletion: enough evidence to claim SYNCED or PENDING. */
    public record DeleteOutcome(long deleted, boolean timedOut, int failures) {
        public boolean clean() {
            return !timedOut && failures == 0;
        }
    }

    /**
     * Deletion with an inspectable outcome for the recycle-bin flow: the
     * response's timedOut flag, bulk failure list, and deleted count are all
     * checked; a clean result is only reported after the refresh has made the
     * deletion visible to search.
     */
    public DeleteOutcome deleteResourceChunksChecked(String resourceType, long resourceId) {
        if (client == null) {
            // No ES wiring (offline/tests): nothing to clean, treat as synced.
            return new DeleteOutcome(0, false, 0);
        }
        try {
            var response = client.deleteByQuery(request -> request
                    .index(ElasticsearchIndexManager.ALIAS)
                    .allowNoIndices(true)
                    .ignoreUnavailable(true)
                    .refresh(true)
                    .query(query -> query.bool(bool -> bool
                            .filter(filter -> filter.term(
                                    term -> term.field("resourceType").value(resourceType)))
                            .filter(filter -> filter.term(
                                    term -> term.field("resourceId")
                                            .value(new FieldValue.Builder()
                                                    .longValue(resourceId).build()))))));
            return new DeleteOutcome(
                    response.deleted(),
                    Boolean.TRUE.equals(response.timedOut()),
                    response.failures() == null ? 0 : response.failures().size());
        } catch (Exception e) {
            throw new IllegalStateException("chunk deletion failed", e);
        }
    }

    /**
     * Knowledge-base-wide deletion covering every parent/child chunk of every
     * resource (pages, attachments, source documents) inside the base.
     */
    public DeleteOutcome deleteKnowledgeBaseChunksChecked(long kbId) {
        if (client == null) {
            return new DeleteOutcome(0, false, 0);
        }
        try {
            var response = client.deleteByQuery(request -> request
                    .index(ElasticsearchIndexManager.ALIAS)
                    .allowNoIndices(true)
                    .ignoreUnavailable(true)
                    .refresh(true)
                    .query(query -> query.term(
                            term -> term.field("kbId")
                                    .value(new FieldValue.Builder().longValue(kbId).build()))));
            return new DeleteOutcome(
                    response.deleted(),
                    Boolean.TRUE.equals(response.timedOut()),
                    response.failures() == null ? 0 : response.failures().size());
        } catch (Exception e) {
            throw new IllegalStateException("knowledge-base chunk deletion failed", e);
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
