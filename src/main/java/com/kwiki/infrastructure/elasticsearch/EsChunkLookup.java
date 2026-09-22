package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import com.kwiki.wiki.api.CitationService;
import org.springframework.beans.factory.ObjectProvider;
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

    public EsChunkLookup(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
    }

    @Override
    public Optional<ChunkHit> byKey(String childChunkKey) {
        if (client == null) {
            return Optional.empty();
        }
        try {
            Map<?, ?> source = client.get(get -> get
                            .index(com.kwiki.indexing.search.ElasticsearchIndexManager.ALIAS)
                            .id(childChunkKey),
                    Map.class)
                    .source();
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
