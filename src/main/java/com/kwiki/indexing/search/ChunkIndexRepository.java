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
 * 分块文档的 Elasticsearch 写入器。写入目标必须是显式命名并通过
 * {@link #requirePhysicalIndex(String)} 验证的物理索引——读别名
 * kwiki-chunks 绝不接受写入，使队列中的任务无法被别名切换重定向。
 * 文档 id 是稳定的 chunk key，因此批量写入是幂等的 UPSERT。资源删除
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

    /**
     * 显式写入目标验证：形如 kwiki-chunks-v{n} 的物理索引名。
     * 读别名（或任何其它名字）直接拒绝。
     */
    public static String requirePhysicalIndex(String physicalIndex) {
        if (physicalIndex == null || !physicalIndex.matches(
                ElasticsearchIndexManager.PHYSICAL_NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "chunk writes require an explicit physical index name"
                            + " (kwiki-chunks-v{n}), got: " + physicalIndex);
        }
        return physicalIndex;
    }

    @Override
    public void upsertChunks(IndexedVersion version, String physicalIndex) {
        String index = requirePhysicalIndex(physicalIndex);
        if (client == null) {
            return;
        }
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder().index(index);
            for (int i = 0; i < version.parents().size(); i++) {
                Map<String, Object> document = ChunkDocument.parent(version.parents().get(i), version);
                bulk.operations(operation -> operation.index(o -> o
                        .id((String) document.get("chunkKey"))
                        .document(document)));
            }
            for (int i = 0; i < version.children().size(); i++) {
                Map<String, Object> document = ChunkDocument.child(
                        version.children().get(i), version.childVectors().get(i), version);
                bulk.operations(operation -> operation.index(o -> o
                        .id((String) document.get("chunkKey"))
                        .document(document)));
            }
            BulkResponse response = client.bulk(bulk.build());
            classify(response);
            log.info("Elasticsearch bulk indexing completed: index={}, resourceType={}, resourceId={}, documents={}",
                    index, version.resourceType(), version.resourceId(),
                    version.parents().size() + version.children().size());
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            throw new IllegalStateException("bulk indexing rejected", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bulk indexing failed", e);
        }
    }

    @Override
    public void deleteResourceChunks(String physicalIndex, String resourceType, long resourceId) {
        String index = requirePhysicalIndex(physicalIndex);
        if (client == null) {
            return;
        }
        try {
            client.deleteByQuery(request -> request
                    .index(index)
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

        public static DeleteOutcome cleanEmpty() {
            return new DeleteOutcome(0, false, 0);
        }
    }

    /**
     * 删除操作带有可检验的结果，供回收站流程使用：
     * 会检查响应里的 timedOut 标志、批量失败列表与删除计数；
     * 只有在 refresh 使删除对搜索可见之后，
     * 才会报告结果为干净。
     */
    public DeleteOutcome deleteResourceChunksChecked(String physicalIndex, String resourceType,
                                                     long resourceId) {
        String index = requirePhysicalIndex(physicalIndex);
        if (client == null) {
            // 未接入 ES（离线/测试）：无需清理，视为已同步。
            return DeleteOutcome.cleanEmpty();
        }
        try {
            var response = client.deleteByQuery(request -> request
                    .index(index)
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
    public DeleteOutcome deleteKnowledgeBaseChunksChecked(String physicalIndex, long kbId) {
        String index = requirePhysicalIndex(physicalIndex);
        if (client == null) {
            return DeleteOutcome.cleanEmpty();
        }
        try {
            var response = client.deleteByQuery(request -> request
                    .index(index)
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
