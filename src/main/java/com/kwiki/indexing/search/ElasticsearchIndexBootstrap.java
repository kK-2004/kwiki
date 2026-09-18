package com.kwiki.indexing.search;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.job.IndexingWorker;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 启动引导（发现/收敛，绝不自动升级）：
 *
 * <ul>
 *   <li>别名与版本元数据都不存在 → 首次部署：创建 v{bootstrapVersion}、
 *       校验 mapping、持久化 configRevision==builtConfigRevision 的已构建
 *       selected/writeEnabled 行，再幂等挂载别名。</li>
 *   <li>ES 已有别名而数据库无元数据 → 升级部署收养：唯一目标且能与当前
 *       部署配置安全匹配（名称 + 维度 + 必需字段）时收养为 selected；
 *       无法证明匹配时按别名事实持久化 selected 行并标记
 *       NEEDS_ATTENTION——读路径不受影响，管理端变更失败关闭。</li>
 *   <li>版本元数据已存在 → 不创建、不重建、不切换；仅当别名完全缺失
 *       且恰有一个 selected 版本时幂等修复别名（崩溃窗口恢复）。
 *       应用重启绝不因配置中的 bootstrap 版本把别名切向更高版本。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "kwiki.indexing.bootstrap.enabled", havingValue = "true",
        matchIfMissing = true)
public class ElasticsearchIndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexBootstrap.class);
    private static final Pattern PHYSICAL_NAME =
            Pattern.compile(ElasticsearchIndexManager.PHYSICAL_NAME_PATTERN);

    private final ElasticsearchIndexManager indexes;
    private final ObjectProvider<SearchIndexVersionRepository> versions;
    private final ChunkMappingBuilder mappingBuilder;
    private final ExternalServicesProperties properties;
    private final int bootstrapVersion;
    private volatile boolean ready;

    public ElasticsearchIndexBootstrap(
            ElasticsearchIndexManager indexes,
            ObjectProvider<SearchIndexVersionRepository> versions,
            ChunkMappingBuilder mappingBuilder,
            ExternalServicesProperties properties,
            @Value("${kwiki.indexing.bootstrap.version:1}") int bootstrapVersion) {
        if (bootstrapVersion < 1) {
            throw new IllegalArgumentException("index version must be positive");
        }
        this.indexes = indexes;
        this.versions = versions;
        this.mappingBuilder = mappingBuilder;
        this.properties = properties;
        this.bootstrapVersion = bootstrapVersion;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!indexes.available()) {
            throw new IllegalStateException(
                    "Elasticsearch index bootstrap requires an Elasticsearch client");
        }
        SearchIndexVersionRepository registry = versions.getIfAvailable();
        if (registry == null) {
            throw new IllegalStateException(
                    "Elasticsearch index bootstrap requires the version registry");
        }
        if (registry.existsByDeletedAtIsNull()) {
            repairAliasAfterCrashWindow(registry);
            ready = true;
            return;
        }
        if (indexes.aliasExists()) {
            adoptExistingAlias(registry);
            ready = true;
            return;
        }
        bootstrapFirstDeployment(registry);
        ready = true;
    }

    /** worker 以此为启动屏障，确保不会抢在别名创建之前消费任务。 */
    public boolean isReady() {
        return ready;
    }

    /** 当前部署实际生效的结构配置（隐式默认代，见任务 1.4 注记）。 */
    private EditableIndexConfig deployedConfig() {
        return new EditableIndexConfig(
                IndexingWorker.PARSER_VERSION,
                IndexingWorker.CHUNKER_VERSION,
                IndexingProperties.DEFAULT_EMBEDDING_PROFILE,
                properties.qwenEmbedding().model(),
                properties.qwenEmbedding().dimensions(),
                1);
    }

    private void bootstrapFirstDeployment(SearchIndexVersionRepository registry) throws Exception {
        EditableIndexConfig config = deployedConfig();
        String mappingHash = mappingBuilder.mappingHash(config.embeddingDimensions());
        String indexName = indexes.indexNameFor(bootstrapVersion);

        indexes.createVersionedIndex(indexName, config.embeddingDimensions());
        String incompatibility = indexes.validateIndex(indexName, config.embeddingDimensions(),
                config.mappingSchemaVersion());
        if (incompatibility != null) {
            throw new IllegalStateException(
                    "Elasticsearch index mapping is incompatible: " + incompatibility);
        }
        registry.save(SearchIndexVersion.bootstrapped(
                bootstrapVersion, indexName, config, mappingHash));
        indexes.createInitialAlias(indexName);
        log.info("Elasticsearch index bootstrap completed: alias {} -> {} (configRevision=1)",
                ElasticsearchIndexManager.ALIAS, indexName);
    }

    private void adoptExistingAlias(SearchIndexVersionRepository registry) throws Exception {
        List<String> targets = indexes.aliasTargets();
        if (targets.size() != 1) {
            log.warn("Elasticsearch alias {} has {} targets {}; recording NEEDS_ATTENTION",
                    ElasticsearchIndexManager.ALIAS, targets.size(), targets);
            persistUnmatched(registry, targets.isEmpty() ? "<missing>" : String.join(",", targets),
                    1, "alias must point to exactly one managed index");
            return;
        }
        String target = targets.get(0);
        java.util.regex.Matcher matcher = PHYSICAL_NAME.matcher(target);
        if (!matcher.matches()) {
            persistUnmatched(registry, target, 1,
                    "alias target name is not a managed kwiki-chunks-v{n} index");
            return;
        }
        int versionNumber = Integer.parseInt(matcher.group(1));
        EditableIndexConfig config = deployedConfig();
        String incompatibility = indexes.validateIndex(target, config.embeddingDimensions(),
                config.mappingSchemaVersion());
        if (incompatibility != null) {
            persistUnmatched(registry, target, versionNumber,
                    "alias target mapping does not match the deployed configuration: "
                            + incompatibility);
            return;
        }
        SearchIndexVersion adopted = SearchIndexVersion.bootstrapped(
                versionNumber, target, config,
                mappingBuilder.mappingHash(config.embeddingDimensions()));
        registry.save(adopted);
        log.info("Elasticsearch alias {} -> {} adopted as version {} (configRevision=1)",
                ElasticsearchIndexManager.ALIAS, target, versionNumber);
    }

    /**
     * 无法安全匹配的遗留状态：以别名事实为准记录 selected 行并标记
     * NEEDS_ATTENTION。检索读路径继续经别名工作；管理端变更因
     * needsAttentionReason 失败关闭，直到管理员修复元数据。
     */
    private void persistUnmatched(SearchIndexVersionRepository registry, String observedTarget,
                                  int versionNumber, String sanitizedReason) {
        EditableIndexConfig config = deployedConfig();
        SearchIndexVersion unmatched = SearchIndexVersion.bootstrapped(
                versionNumber, observedTarget, config,
                mappingBuilder.mappingHash(config.embeddingDimensions()));
        unmatched.markUnmatchedLegacyState(sanitizedReason);
        registry.save(unmatched);
        log.warn("Elasticsearch alias {} -> {} recorded as NEEDS_ATTENTION: {}",
                ElasticsearchIndexManager.ALIAS, observedTarget, sanitizedReason);
    }

    /**
     * 元数据已存在时的崩溃窗口恢复：别名完全缺失且恰有一个 selected
     * 版本 → 幂等重新挂载；否则留给别名对账（切换阶段）处理。
     */
    private void repairAliasAfterCrashWindow(SearchIndexVersionRepository registry) throws Exception {
        if (indexes.aliasExists()) {
            return;
        }
        var selected = registry.findBySelectedTrue()
                .filter(version -> version.getDeletedAt() == null);
        if (selected.isPresent()) {
            String indexName = selected.get().getPhysicalName();
            indexes.createInitialAlias(indexName);
            log.warn("Elasticsearch alias {} was missing; repaired to selected index {}",
                    ElasticsearchIndexManager.ALIAS, indexName);
        } else {
            log.error("Elasticsearch alias {} is missing and no selected version exists;"
                    + " administrative reconciliation required", ElasticsearchIndexManager.ALIAS);
        }
    }
}
