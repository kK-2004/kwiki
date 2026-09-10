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
 * 分块文档的 Elasticsearch 写入器。文档 id 是稳定的 chunk
 * key（index = alias），因此批量写入是幂等的 UPSERT。资源删除
 * 同时按 resourceType 与 resourceId 过滤，绝不会删除其他资源的分块。
 * 批量写入的部分失败会被分类：临时性状态码以可重试异常抛出，
 * 永久性拒绝则快速失败。
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

    /** 带检验的删除结果：足以判定为 SYNCED 或 PENDING 的证据。 */
    public record DeleteOutcome(long deleted, boolean timedOut, int failures) {
        public boolean clean() {
            return !timedOut && failures == 0;
        }
    }

    /**
     * 删除操作带有可检验的结果，供回收站流程使用：
     * 会检查响应里的 timedOut 标志、批量失败列表与删除计数；
     * 只有在 refresh 使删除对搜索可见之后，
     * 才会报告结果为干净。
     */
    public DeleteOutcome deleteResourceChunksChecked(String resourceType, long resourceId) {
        if (client == null) {
            // 未接入 ES（离线/测试）：无需清理，视为已同步。
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
     * 知识库范围的删除，覆盖该库内每个资源的
     * 每一个父/子分块（页面、附件、源文档）。
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
