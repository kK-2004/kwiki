package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import com.kwiki.graph.CommunityIndexWriteRequest;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.persistence.GraphBuildMetrics;
import com.kwiki.graph.persistence.GraphOfflineModelLimiter;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 社区摘要的独立 embedding、批写、refresh 和读回校验入口。 */
@Component
public class CommunityIndexWriter {

    private final ElasticsearchClient client;
    private final ChunkEmbeddingPort embeddings;
    private final GraphOfflineModelLimiter limiter;
    private final GraphBuildMetrics metrics;

    public CommunityIndexWriter(ObjectProvider<ElasticsearchClient> client,
                                ObjectProvider<ChunkEmbeddingPort> embeddings,
                                ObjectProvider<GraphOfflineModelLimiter> limiter,
                                ObjectProvider<GraphBuildMetrics> metrics) {
        this.client = client.getIfAvailable();
        this.embeddings = embeddings.getIfAvailable();
        this.limiter = limiter.getIfAvailable();
        this.metrics = metrics.getIfAvailable();
    }

    public WriteResult write(List<CommunityIndexWriteRequest> requests) {
        if (requests == null || requests.isEmpty()) return new WriteResult(0, true);
        if (client == null) return new WriteResult(0, false);
        if (embeddings == null) throw new IllegalStateException("社区摘要 embedding 客户端不可用");
        String physicalIndex = requests.get(0).physicalIndex();
        if (!CommunityIndexNaming.isManaged(physicalIndex)) {
            throw new IllegalArgumentException("社区摘要只能写入受控 COMMUNITY 物理索引");
        }
        if (requests.stream().anyMatch(request -> !physicalIndex.equals(request.physicalIndex()))) {
            throw new IllegalArgumentException("一次批写不能混合多个社区物理索引");
        }
        List<String> texts = requests.stream().map(CommunityIndexWriter::embeddingText).toList();
        List<float[]> vectors = limiter == null ? embeddings.embed(texts)
                : limiter.execute(() -> embeddings.embed(texts));
        if (vectors.size() != requests.size()) {
            throw new IllegalStateException("社区摘要 embedding 数量不匹配");
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder().index(physicalIndex);
        for (int i = 0; i < requests.size(); i++) {
            CommunityIndexWriteRequest request = requests.get(i);
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != request.embeddingDimensions()) {
                throw new IllegalStateException("社区摘要 embedding 维度不匹配");
            }
            bulk.operations(operation -> operation.index(index -> index
                    .id(request.communityKey()).document(document(request, vector))));
        }
        try {
            BulkResponse response = client.bulk(bulk.build());
            classify(response);
            client.indices().refresh(refresh -> refresh.index(physicalIndex));
            for (CommunityIndexWriteRequest request : requests) verifyReadable(request);
            if (metrics != null) metrics.recordCommunityWrite(requests.size());
            return new WriteResult(requests.size(), true);
        } catch (Exception failure) {
            throw new IllegalStateException("社区摘要索引写入或读回校验失败", failure);
        }
    }

    private void verifyReadable(CommunityIndexWriteRequest request) throws Exception {
        var response = client.search(search -> search.index(request.physicalIndex()).size(1)
                .query(query -> query.term(term -> term.field("communityKey")
                        .value(request.communityKey()))), Map.class);
        if (response.hits().hits().isEmpty()) {
            throw new IllegalStateException("社区摘要 refresh 后不可读: " + request.communityKey());
        }
    }

    private static Map<String, Object> document(CommunityIndexWriteRequest request, float[] vector) {
        CommunitySummary summary = request.summary();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("communityKey", request.communityKey());
        document.put("kbId", request.kbId());
        document.put("graphVersion", request.graphVersion());
        document.put("communityIndexVersion", request.communityIndexVersion());
        document.put("communityId", request.communityId());
        document.put("sourceManifestId", request.sourceManifestId());
        document.put("sourceContentEpoch", request.sourceContentEpoch());
        document.put("sourceSecurityEpoch", request.sourceSecurityEpoch());
        document.put("sourceChunkIndexVersion", request.sourceChunkIndexVersion());
        document.put("title", summary.title());
        document.put("summary", summary.summary());
        document.put("keywords", summary.keywords());
        document.put("coreConcepts", summary.coreConcepts());
        document.put("importantRelations", summary.importantRelations());
        document.put("questionTypes", summary.questionTypes());
        document.put("representativeEntityIds", request.representativeEntityIds());
        document.put("sourceRefs", summary.sourceRefs().stream().map(CommunityIndexWriter::sourceRef).toList());
        document.put("entityCount", request.entityCount());
        document.put("summaryModel", request.summaryModel());
        document.put("summaryPromptVersion", request.summaryPromptVersion());
        document.put("embeddingModel", request.embeddingModel());
        document.put("embeddingDimensions", request.embeddingDimensions());
        document.put("vector", vector);
        return document;
    }

    private static Map<String, Object> sourceRef(GraphSourceRef ref) {
        return Map.of("sourceChunkId", ref.sourceChunkId(), "startOffset", ref.startOffset(),
                "endOffset", ref.endOffset());
    }

    private static String embeddingText(CommunityIndexWriteRequest request) {
        CommunitySummary summary = request.summary();
        return summary.title() + "\n" + summary.summary() + "\n"
                + String.join(" ", summary.keywords()) + "\n"
                + String.join(" ", summary.coreConcepts());
    }

    private static void classify(BulkResponse response) {
        for (var item : response.items()) {
            ErrorCause error = item.error();
            if (error == null) continue;
            if (item.status() == 429 || item.status() >= 500) {
                throw new IllegalStateException("retryable community bulk failure: " + error.type());
            }
            throw new IllegalStateException("permanent community bulk failure: " + error.type());
        }
    }

    public record WriteResult(int written, boolean verified) {
    }
}
