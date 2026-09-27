package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import com.kwiki.wiki.api.CitationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通过稳定的 chunk key（文档 id）解析引用块文档。
 * 缺失或不可达的索引解析为空，表现为安全的 404。
 */
@Component
public class EsChunkLookup implements CitationService.ChunkLookup {

    private final ElasticsearchClient client;
    private final com.kwiki.indexing.gray.GrayReadRoutes routes;

    public EsChunkLookup(ObjectProvider<ElasticsearchClient> client) {
        this(client, null);
    }

    /** 带灰度读路由：已切换灰度的知识库从其灰度物理索引解析引用块。 */
    @Autowired
    public EsChunkLookup(ObjectProvider<ElasticsearchClient> client,
                         @org.springframework.lang.Nullable com.kwiki.indexing.gray.GrayReadRoutes routes) {
        this.client = client.getIfAvailable();
        this.routes = routes;
    }

    @Override
    public Optional<ChunkHit> byKey(String childChunkKey) {
        if (client == null) {
            return Optional.empty();
        }
        try {
            com.kwiki.indexing.gray.ReadRouting routing = routes == null
                    ? com.kwiki.indexing.gray.ReadRouting.none() : routes.current();
            Map<?, ?> source;
            if (routing.isEmpty()) {
                // 无灰度路由：保持原有在别名上的实时 GET
                source = client.get(get -> get
                                .index(com.kwiki.indexing.search.ElasticsearchIndexManager.ALIAS)
                                .id(childChunkKey),
                        Map.class)
                        .source();
            } else {
                // 有灰度路由：GET 不能跨索引附加过滤，改为按 id 搜索并附加路由过滤
                List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = client.search(request -> request
                                .index(EsReadRouting.indices(routing))
                                .size(1)
                                .query(query -> query.bool(bool -> {
                                    bool.filter(filter -> filter.ids(ids -> ids.values(childChunkKey)));
                                    EsReadRouting.filter(routing).ifPresent(bool::filter);
                                    return bool;
                                })),
                        Map.class).hits().hits();
                source = hits.isEmpty() ? null : hits.get(0).source();
            }
            if (source == null) {
                return Optional.empty();
            }
            return Optional.of(new ChunkHit(
                    String.valueOf(source.get("chunkKey")),
                    String.valueOf(source.get("parentChunkKey")),
                    longValue(source.get("kbId")),
                    String.valueOf(source.get("resourceType")),
                    longValue(source.get("resourceId")),
                    source.get("revisionId") == null
                            ? null : longValue(source.get("revisionId")),
                    String.valueOf(source.get("headingPath")),
                    (int) longValue(source.get("charStart")),
                    (int) longValue(source.get("charEnd")),
                    String.valueOf(source.get("content")),
                    contentIds(source),
                    optionalText(source.get("sourceChunkId")),
                    entityIds(source),
                    optionalText(source.get("entityLinkingVersion")),
                    entityStatus(source)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 旧索引无 contentIds 字段时安全回退为空列表。 */
    private static List<Long> contentIds(Map<?, ?> source) {
        Object value = source.get("contentIds");
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(item -> longValue(item))
                    .distinct()
                    .toList();
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

    private static EntityLinkingStatus entityStatus(Map<?, ?> source) {
        Object value = source.get("entityLinkingStatus");
        if (value == null) {
            return EntityLinkingStatus.MISSING;
        }
        try {
            return EntityLinkingStatus.valueOf(String.valueOf(value).toUpperCase(
                    java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return EntityLinkingStatus.FAILED;
        }
    }

    private static String optionalText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
