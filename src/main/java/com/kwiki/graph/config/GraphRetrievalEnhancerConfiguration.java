package com.kwiki.graph.config;

import com.kwiki.graph.CommunityRecallCoordinator;
import com.kwiki.graph.CommunitySearchPort;
import com.kwiki.graph.GraphExpansionPort;
import com.kwiki.graph.GraphSnapshotPin;
import com.kwiki.graph.GraphSourceChildResolverPort;
import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.GraphSnapshotManifestPort;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphSnapshotReadService;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.rag.retrieval.GraphRetrievalEnhancer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 图增强装配：仅当 kwiki.graph.enabled=true 且全部端口可用时创建
 * GraphRetrievalEnhancer。默认关闭时不连接 ArcadeDB，工作流保持原有
 * Chunk 检索路径。
 */
@Configuration
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphRetrievalEnhancerConfiguration {

    @Bean
    public GraphSnapshotManifestPort graphSnapshotManifestPort(GraphBuildRepository repository) {
        return repository::findManifestResourceIds;
    }

    @Bean
    public GraphRetrievalEnhancer.SnapshotPinner graphSnapshotPinner(
            GraphSnapshotReadService reader,
            ObjectProvider<SearchIndexVersionRepository> chunkVersions) {
        return new GraphRetrievalEnhancer.SnapshotPinner() {
            @Override
            public Map<Long, Optional<GraphSnapshotPin>> pinAll(Collection<Long> kbIds) {
                Integer selected = chunkVersions.stream()
                        .map(SearchIndexVersionRepository::findBySelectedTrue)
                        .flatMap(Optional::stream)
                        .map(version -> version.getVersionNumber())
                        .findFirst()
                        .orElse(null);
                Map<Long, Long> targets = new LinkedHashMap<>();
                for (Long kbId : kbIds) {
                    if (selected != null) {
                        targets.put(kbId, selected.longValue());
                    }
                }
                return selected == null ? new LinkedHashMap<>() : reader.pinAll(targets);
            }

            @Override
            public void release(Iterable<GraphSnapshotPin> pins) {
                reader.releaseAll(pins);
            }
        };
    }

    @Bean
    public GraphRetrievalEnhancer graphRetrievalEnhancer(
            GraphRetrievalEnhancer.SnapshotPinner pinner,
            GraphSourceInventoryPort inventory,
            GraphSnapshotManifestPort manifests,
            ObjectProvider<GraphSourceChildResolverPort> childResolver,
            ObjectProvider<CommunitySearchPort> communitySearch,
            ObjectProvider<GraphExpansionPort> expansion,
            ObjectProvider<ChunkEmbeddingPort> embeddings,
            GraphProperties properties) {
        return new GraphRetrievalEnhancer(
                pinner, inventory, manifests,
                childResolver.getIfAvailable(() -> (index, id, version, kb) -> Optional.empty()),
                new CommunityRecallCoordinator(
                        communitySearch.getIfAvailable(
                                () -> request -> new com.kwiki.graph.CommunitySearchResult(
                                        java.util.List.of(), true, "community-search-unavailable")),
                        embeddings.getIfAvailable(() -> {
                            throw new IllegalStateException("缺少查询嵌入端口");
                        })),
                expansion.getIfAvailable(() -> request -> {
                    throw new IllegalStateException("图扩展端口不可用");
                }),
                properties.capacity().maxHops(),
                properties.capacity().maxEdgesPerNode(),
                properties.capacity().maxEdgesTotal(),
                20, 30);
    }
}
