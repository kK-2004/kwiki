package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchCapacityGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 管理端的版本创建/编辑服务（任务 6.1 的服务端核心）：校验请求配置
 * 受当前部署支持、容量预检失败关闭、自动分配下一个单调版本号、
 * 强制编辑限制并原子递增 configRevision。创建/编辑绝不触碰读别名，
 * 也不创建/重建物理索引（那是显式重建动作的事）。管理 API 层叠加
 * ROLE_ADMIN、幂等键与审计（phase 10）。
 */
@Service
public class SearchIndexAdminService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexAdminService.class);

    public static final String MUTATIONS_DISABLED_MESSAGE =
            "index management mutations are disabled by configuration";

    private final SearchIndexVersionService versions;
    private final VersionedIndexingPipelineRegistry pipelines;
    private final ElasticsearchCapacityGuard capacity;
    private final IndexingProperties indexingProperties;
    private final ObjectProvider<SearchIndexVersionRepository> registry;

    public SearchIndexAdminService(SearchIndexVersionService versions,
                                   VersionedIndexingPipelineRegistry pipelines,
                                   ElasticsearchCapacityGuard capacity,
                                   IndexingProperties indexingProperties,
                                   ObjectProvider<SearchIndexVersionRepository> registry) {
        this.versions = versions;
        this.pipelines = pipelines;
        this.capacity = capacity;
        this.indexingProperties = indexingProperties;
        this.registry = registry;
    }

    /**
     * 创建下一个自动编号的版本。配置必须精确匹配一个受支持结构代；
     * 创建前执行 ES 健康与容量预检（失败关闭）。
     */
    public SearchIndexVersion createVersion(EditableIndexConfig config) {
        requireMutationsEnabled();
        requireSupported(config);
        requireCapacityAllowsOneMoreIndex();
        SearchIndexVersion created = versions.createNext(config);
        log.info("index version {} created (configRevision=1, pending rebuild)",
                created.getVersionNumber());
        return created;
    }

    /**
     * 编辑符合条件的离线版本：保留版本号，原子递增 configRevision，
     * 物理内容视为过期（待重建）。在线/写入/运行中版本被拒绝并建议
     * 复制配置创建下一版本。
     */
    public SearchIndexVersion editVersion(int versionNumber, EditableIndexConfig config) {
        requireMutationsEnabled();
        requireSupported(config);
        SearchIndexVersion edited = versions.edit(versionNumber, config);
        log.info("index version {} edited (configRevision={}, pending rebuild)",
                edited.getVersionNumber(), edited.getConfigRevision());
        return edited;
    }

    private void requireMutationsEnabled() {
        if (!Boolean.TRUE.equals(indexingProperties.management().mutationsEnabled())) {
            throw new IllegalStateException(MUTATIONS_DISABLED_MESSAGE);
        }
    }

    private void requireSupported(EditableIndexConfig config) {
        if (!pipelines.supports(config)) {
            throw new IllegalArgumentException("unsupported index configuration: "
                    + pipelines.unsupportedReason(config).orElse("unknown"));
        }
    }

    private void requireCapacityAllowsOneMoreIndex() {
        SearchIndexVersionRepository repository = registry.getIfAvailable();
        int managed = repository == null ? 0
                : (int) repository.findByDeletedAtIsNullOrderByVersionNumberAsc().size();
        ElasticsearchCapacityGuard.PreflightResult preflight = capacity.preflight(managed);
        if (!preflight.ok()) {
            throw new IllegalStateException("capacity preflight failed: " + preflight.reason());
        }
    }

    /** 管理端展示用：版本快照 + 派生主展示状态。 */
    public Optional<IndexDisplayStatus> displayStatus(int versionNumber) {
        SearchIndexVersionRepository repository = registry.getIfAvailable();
        if (repository == null) {
            return Optional.empty();
        }
        return repository.findByVersionNumber(versionNumber)
                .map(version -> IndexVersionStatusPolicy.displayStatus(
                        version.toSnapshot(false, false)));
    }
}
