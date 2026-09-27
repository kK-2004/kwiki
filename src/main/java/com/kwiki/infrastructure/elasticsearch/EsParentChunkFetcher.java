package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.ParentEvidenceResolver;
import com.kwiki.rag.retrieval.RetrievalLifecycleService;
import com.kwiki.rag.retrieval.ScopeFilter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生产环境的父块获取：先在 chunks 别名上按 chunkKey 进行 multi-get，再
 * 在返回任何结果前重新应用调用方的范围过滤器（scope filter）与权威的
 * 生命周期排除项。已归档、超出范围或缺失的父块会被省略——绝不替换。
 */
@Component
public class EsParentChunkFetcher implements ParentEvidenceResolver.ParentChunkFetcher {

    private final ElasticsearchClient client;
    private final RetrievalLifecycleService lifecycle;
    private final com.kwiki.indexing.gray.GrayReadRoutes routes;

    public EsParentChunkFetcher(ObjectProvider<ElasticsearchClient> client,
                                RetrievalLifecycleService lifecycle) {
        this(client, lifecycle, null);
    }

    /** 带灰度读路由：已切换灰度的知识库从其灰度物理索引读取父块，避免新解析子块配旧解析父块。 */
    @Autowired
    public EsParentChunkFetcher(ObjectProvider<ElasticsearchClient> client,
                                RetrievalLifecycleService lifecycle,
                                @org.springframework.lang.Nullable com.kwiki.indexing.gray.GrayReadRoutes routes) {
        this.client = client.getIfAvailable();
        this.lifecycle = lifecycle;
        this.routes = routes;
    }

    @Override
    public List<ParentEvidenceChunk> fetchByKeys(List<String> parentChunkKeys,
                                                 ScopeFilter scopeFilter) {
        if (client == null || parentChunkKeys == null || parentChunkKeys.isEmpty()) {
            return List.of();
        }
        var exclusions = lifecycle.exclusions();
        try {
            com.kwiki.indexing.gray.ReadRouting routing = routes == null
                    ? com.kwiki.indexing.gray.ReadRouting.none() : routes.current();
            List<Map<?, ?>> sources = routing.isEmpty()
                    ? viaAlias(parentChunkKeys) : viaRouting(parentChunkKeys, routing);
            List<ParentEvidenceChunk> parents = new ArrayList<>();
            for (Map<?, ?> source : sources) {
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

    /** 无灰度路由：保持原有在别名上的 multi-get。 */
    private List<Map<?, ?>> viaAlias(List<String> parentChunkKeys) throws java.io.IOException {
        var response = client.mget(request -> request
                .index(ElasticsearchIndexManager.ALIAS)
                .ids(parentChunkKeys),
                Map.class);
        List<Map<?, ?>> sources = new ArrayList<>();
        for (MultiGetResponseItem<Map> item : response.docs()) {
            if (item.isFailure() || item.result() == null || item.result().source() == null) {
                continue;
            }
            sources.add(item.result().source());
        }
        return sources;
    }

    /**
     * 有灰度路由：multi-get 不能跨索引附加过滤，改为在别名 + 灰度索引上按 id 搜索并附加路由过滤；
     * 结果按请求键的顺序返回，与 multi-get 的顺序语义一致。
     */
    private List<Map<?, ?>> viaRouting(List<String> parentChunkKeys,
                                       com.kwiki.indexing.gray.ReadRouting routing) throws java.io.IOException {
        List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = client.search(request -> request
                        .index(EsReadRouting.indices(routing))
                        // 灰度物理索引缺失时忽略该索引，不拖垮全部检索
                        .ignoreUnavailable(true)
                        .size(parentChunkKeys.size())
                        .query(query -> query.bool(bool -> {
                            bool.filter(filter -> filter.ids(ids -> ids.values(parentChunkKeys)));
                            EsReadRouting.filter(routing).ifPresent(bool::filter);
                            return bool;
                        })),
                Map.class).hits().hits();
        Map<String, Map<?, ?>> byId = new java.util.HashMap<>();
        for (co.elastic.clients.elasticsearch.core.search.Hit<Map> hit : hits) {
            if (hit.source() != null) {
                byId.putIfAbsent(hit.id(), hit.source());
            }
        }
        List<Map<?, ?>> sources = new ArrayList<>();
        for (String key : parentChunkKeys) {
            Map<?, ?> source = byId.get(key);
            if (source != null) {
                sources.add(source);
            }
        }
        return sources;
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
