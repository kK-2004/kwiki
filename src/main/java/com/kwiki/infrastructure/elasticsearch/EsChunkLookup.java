package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.wiki.api.CitationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
                    String.valueOf(source.get("content"))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
