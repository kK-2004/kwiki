package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.kwiki.graph.GraphSourceChildResolverPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 图关系来源 → 当前有效 CHILD 的解析：仅在快照固定的 Chunk 物理索引上按
 * sourceChunkId 精确查询，并核对 kbId 与实体映射代际；任何不匹配都返回
 * empty，不用旧 entityIds 配新正文。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
@ConditionalOnBean(ElasticsearchClient.class)
public class EsGraphSourceChildResolver implements GraphSourceChildResolverPort {

    private final ElasticsearchClient client;

    public EsGraphSourceChildResolver(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
    }

    @Override
    public Optional<ChunkHit> resolve(String physicalIndex, String sourceChunkId,
                                      String expectedEntityLinkingVersion, long kbId) {
        if (client == null || physicalIndex == null || physicalIndex.isBlank()
                || sourceChunkId == null || sourceChunkId.isBlank()) {
            return Optional.empty();
        }
        try {
            var response = client.search(search -> search
                            .index(physicalIndex).size(1)
                            .query(query -> query.term(term -> term
                                    .field("sourceChunkId").value(sourceChunkId))),
                    Map.class);
            for (var hit : response.hits().hits()) {
                Map<?, ?> source = hit.source();
                if (source == null || longValue(source.get("kbId")) != kbId) {
                    continue;
                }
                String version = text(source.get("entityLinkingVersion"));
                if (expectedEntityLinkingVersion != null
                        && !expectedEntityLinkingVersion.equals(version)) {
                    continue;
                }
                return Optional.of(new ChunkHit(
                        text(source.get("chunkKey")), text(source.get("parentChunkKey")),
                        longValue(source.get("kbId")), text(source.get("resourceType")),
                        longValue(source.get("resourceId")),
                        source.get("revisionId") == null
                                ? null : longValue(source.get("revisionId")),
                        text(source.get("headingPath")),
                        (int) longValue(source.get("charStart")),
                        (int) longValue(source.get("charEnd")),
                        text(source.get("content")),
                        contentIds(source), text(source.get("sourceChunkId")),
                        entityIds(source), version, status(source)));
            }
            return Optional.empty();
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    private static EntityLinkingStatus status(Map<?, ?> source) {
        String value = text(source.get("entityLinkingStatus"));
        return value == null || value.isBlank() ? EntityLinkingStatus.MISSING
                : EntityLinkingStatus.valueOf(value);
    }

    private static List<Long> contentIds(Map<?, ?> source) {
        if (!(source.get("contentIds") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull)
                .map(value -> longValue(value)).distinct().toList();
    }

    private static List<String> entityIds(Map<?, ?> source) {
        if (!(source.get("entityIds") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .distinct().sorted().toList();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }
}
