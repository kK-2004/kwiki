package com.kwiki.graph.persistence;

import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 图构建产物的外部数据清理：ArcadeDB 中某库某个 graphVersion 的图数据，和社区 ES 物理索引。
 * 批次删除与快照退役清理共用；同时作为快照资源清单的清理端口，按资源种类分派。
 * 清理器缺失（如离线装配）时视为无数据可删。失败抛出异常，由调用方记录并允许重试。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphArtifactCleaner implements GraphSnapshotResourceCleanupPort {

    /** 快照资源清单中的资源种类；identity 分别为 {@code kbId:graphVersion} 与物理索引名。 */
    public static final String KIND_GRAPH_VERSION = "ARCADEDB_GRAPH_VERSION";
    public static final String KIND_COMMUNITY_INDEX = "COMMUNITY_PHYSICAL_INDEX";

    private final GraphVersionDataCleaner graphCleaner;
    private final ElasticsearchIndexManager indexes;

    @org.springframework.beans.factory.annotation.Autowired
    public GraphArtifactCleaner(ObjectProvider<GraphVersionDataCleaner> graphCleaner,
                                ObjectProvider<ElasticsearchIndexManager> indexes) {
        this(graphCleaner.getIfAvailable(), indexes.getIfAvailable());
    }

    GraphArtifactCleaner(GraphVersionDataCleaner graphCleaner, ElasticsearchIndexManager indexes) {
        this.graphCleaner = graphCleaner;
        this.indexes = indexes;
    }

    public static String graphVersionIdentity(long kbId, long graphVersion) {
        return kbId + ":" + graphVersion;
    }

    /** 删除 {@code (kbId, graphVersion)} 的图数据；幂等。 */
    public void deleteGraphVersion(long kbId, long graphVersion) {
        if (graphCleaner != null) {
            graphCleaner.deleteGraphVersion(kbId, graphVersion);
        }
    }

    /** 删除社区 ES 物理索引；只接受托管命名，索引不存在视为成功。 */
    public void deleteCommunityIndex(String physicalIndex) {
        if (indexes == null || physicalIndex == null || physicalIndex.isBlank()) {
            return;
        }
        try {
            indexes.forceDeleteManagedIndex(physicalIndex);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    @Override
    public boolean delete(String resourceKind, String resourceIdentity) {
        switch (resourceKind) {
            case KIND_GRAPH_VERSION -> {
                int separator = resourceIdentity.indexOf(':');
                if (separator <= 0) {
                    throw new IllegalArgumentException("图版本资源标识无效：" + resourceIdentity);
                }
                deleteGraphVersion(Long.parseLong(resourceIdentity.substring(0, separator)),
                        Long.parseLong(resourceIdentity.substring(separator + 1)));
            }
            case KIND_COMMUNITY_INDEX -> deleteCommunityIndex(resourceIdentity);
            default -> throw new IllegalArgumentException("未知的快照资源种类：" + resourceKind);
        }
        return true;
    }
}
