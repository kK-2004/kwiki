package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.ParentEvidenceResolver;
import com.kwiki.rag.retrieval.RetrievalLifecycleService;
import com.kwiki.rag.retrieval.ScopeFilter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Production parent fetch: multi-get by chunkKey on the chunks alias, then
 * re-apply the caller's scope filter AND the authoritative lifecycle
 * exclusions before anything is returned. A parent that is archived, out of
 * scope, or missing is omitted — never substituted.
 */
@Component
public class EsParentChunkFetcher implements ParentEvidenceResolver.ParentChunkFetcher {

    private final ElasticsearchClient client;
    private final RetrievalLifecycleService lifecycle;

    public EsParentChunkFetcher(ObjectProvider<ElasticsearchClient> client,
                                RetrievalLifecycleService lifecycle) {
        this.client = client.getIfAvailable();
        this.lifecycle = lifecycle;
    }

    @Override
    public List<ParentEvidenceChunk> fetchByKeys(List<String> parentChunkKeys,
                                                 ScopeFilter scopeFilter) {
        if (client == null || parentChunkKeys == null || parentChunkKeys.isEmpty()) {
            return List.of();
        }
        var exclusions = lifecycle.exclusions();
        try {
            var response = client.mget(request -> request
                    .index(ElasticsearchIndexManager.ALIAS)
                    .ids(parentChunkKeys),
                    Map.class);
            List<ParentEvidenceChunk> parents = new ArrayList<>();
            for (MultiGetResponseItem<Map> item : response.docs()) {
                if (item.isFailure() || item.result() == null || item.result().source() == null) {
                    continue;
                }
                Map<?, ?> source = item.result().source();
                ParentEvidenceChunk chunk = toChunk(source);
                if (chunk != null && inScope(chunk, scopeFilter, exclusions)) {
                    parents.add(chunk);
                }
            }
            return parents;
        } catch (Exception e) {
            throw new IllegalStateException("parent chunk fetch failed", e);
        }
    }

    private boolean inScope(ParentEvidenceChunk chunk, ScopeFilter scope,
                            RetrievalLifecycleService.Exclusions exclusions) {
        if (exclusions.archivedKbIds().contains(chunk.kbId())) {
            return false;
        }
        if ("PAGE".equals(chunk.resourceType())
                && exclusions.archivedPageIds().contains(chunk.resourceId())) {
            return false;
        }
        if (scope.superuser()) {
            return true;
        }
        if (scope.kbIds().contains(chunk.kbId())) {
            return !"PAGE".equals(chunk.resourceType())
                    || scope.pageIds().isEmpty()
                    || scope.pageIds().contains(chunk.resourceId());
        }
        return "PAGE".equals(chunk.resourceType()) && scope.pageIds().contains(chunk.resourceId());
    }

    private static ParentEvidenceChunk toChunk(Map<?, ?> source) {
        Object chunkKey = source.get("chunkKey");
        if (chunkKey == null) {
            return null;
        }
        return new ParentEvidenceChunk(
                String.valueOf(chunkKey),
                longOf(source.get("kbId")),
                stringOf(source.get("resourceType")),
                longOf(source.get("resourceId")),
                source.get("revisionId") == null ? null : longOf(source.get("revisionId")),
                stringOf(source.get("headingPath")),
                stringOf(source.get("content")),
                0.0,
                List.of());
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
