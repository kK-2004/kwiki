package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.kwiki.graph.GraphSourceChild;
import com.kwiki.graph.GraphSourceChunkListingPort;
import com.kwiki.graph.build.GraphBuildFailure;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按知识库列出 CHUNK 物理索引中全部 CHILD 文档，作为图构建的来源清单输入。
 * chunkKey 升序 + search_after 分页；来源总数超过上限时永久失败，不做裁剪。
 * 每条结果同时携带还原稳定 sourceChunkId 所需的文档字段。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class EsGraphSourceChunkListing implements GraphSourceChunkListingPort {

    private static final int PAGE_SIZE = 500;

    private final ElasticsearchClient client;

    public EsGraphSourceChunkListing(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
    }

    @Override
    public List<GraphSourceChild> listChildren(String chunkPhysicalIndex, long kbId, int maxResults) {
        if (client == null) {
            throw GraphBuildFailure.retryable("ELASTICSEARCH_UNAVAILABLE", "Elasticsearch 未配置");
        }
        List<GraphSourceChild> children = new ArrayList<>();
        List<co.elastic.clients.elasticsearch._types.FieldValue> searchAfter = null;
        try {
            while (true) {
                SearchResponse<Map> response = client.search(
                        request(chunkPhysicalIndex, kbId, searchAfter).build(), Map.class);
                checkCapacity(response, maxResults);
                List<Hit<Map>> page = response.hits().hits();
                page.forEach(hit -> children.add(toChild(hit.source())));
                if (page.size() < PAGE_SIZE) {
                    break;
                }
                searchAfter = page.get(page.size() - 1).sort();
            }
        } catch (GraphBuildFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw GraphBuildFailure.retryable("ELASTICSEARCH_UNAVAILABLE",
                    "列举来源块失败：" + failure.getMessage());
        }
        children.sort(java.util.Comparator.comparing(child -> child.hit().chunkKey()));
        return List.copyOf(children);
    }

    private static SearchRequest.Builder request(String chunkPhysicalIndex, long kbId,
                                                 List<co.elastic.clients.elasticsearch._types.FieldValue> searchAfter) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(chunkPhysicalIndex)
                .size(PAGE_SIZE)
                .trackTotalHits(total -> total.enabled(true))
                .query(query -> query.bool(bool -> bool
                        .filter(filter -> filter.term(term -> term.field("chunkLevel").value("CHILD")))
                        .filter(filter -> filter.term(term -> term.field("kbId").value(kbId)))))
                .sort(sort -> sort.field(field -> field.field("chunkKey").order(SortOrder.Asc)));
        if (searchAfter != null) {
            builder.searchAfter(searchAfter);
        }
        return builder;
    }

    private static void checkCapacity(SearchResponse<Map> response, int maxResults) {
        if (response.hits().total() != null && response.hits().total().value() > maxResults) {
            throw GraphBuildFailure.permanent("CAPACITY_EXCEEDED_SOURCES",
                    "来源块数超过上限 " + maxResults);
        }
    }

    /** _source 检索块字段映射与 {@link EsChunkLookup} 保持一致。 */
    private static GraphSourceChild toChild(Map<?, ?> source) {
        return new GraphSourceChild(
                new com.kwiki.rag.retrieval.ChunkHit(
                        String.valueOf(source.get("chunkKey")),
                        String.valueOf(source.get("parentChunkKey")),
                        longValue(source.get("kbId")),
                        String.valueOf(source.get("resourceType")),
                        longValue(source.get("resourceId")),
                        source.get("revisionId") == null ? null : longValue(source.get("revisionId")),
                        String.valueOf(source.get("headingPath")),
                        (int) longValue(source.get("charStart")),
                        (int) longValue(source.get("charEnd")),
                        String.valueOf(source.get("content")),
                        contentIds(source),
                        optionalText(source.get("sourceChunkId")),
                        entityIds(source),
                        optionalText(source.get("entityLinkingVersion")),
                        entityStatus(source)),
                longValue(source.get("lifecycleVersion")),
                optionalText(source.get("parserVersion")),
                optionalText(source.get("chunkerVersion")));
    }

    private static List<Long> contentIds(Map<?, ?> source) {
        Object value = source.get("contentIds");
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(EsGraphSourceChunkListing::longValue).distinct().toList();
        }
        return List.of();
    }

    private static List<String> entityIds(Map<?, ?> source) {
        Object value = source.get("entityIds");
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).distinct().sorted().toList();
        }
        return List.of();
    }

    private static com.kwiki.rag.retrieval.EntityLinkingStatus entityStatus(Map<?, ?> source) {
        Object value = source.get("entityLinkingStatus");
        if (value == null) {
            return com.kwiki.rag.retrieval.EntityLinkingStatus.MISSING;
        }
        try {
            return com.kwiki.rag.retrieval.EntityLinkingStatus.valueOf(
                    String.valueOf(value).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return com.kwiki.rag.retrieval.EntityLinkingStatus.FAILED;
        }
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String optionalText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
