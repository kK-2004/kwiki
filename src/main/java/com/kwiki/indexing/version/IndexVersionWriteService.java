package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 管理员显式控制版本是否承接实时索引写入（双写）。系统中不再存在任何隐式开启写入的路径。
 * 开启时先清空物理索引，再在锁定版本行的事务中加入写目标集合并记录事件水位 E：
 * 实时入队读取写目标时与该行锁互斥，因此 E 之前的事件不会写入本版本，E 之后的事件一定会。
 */
@Service
public class IndexVersionWriteService {

    private static final List<String> ACTIVE_RUN_STATES = List.of(
            RebuildRunState.PENDING.name(), RebuildRunState.RUNNING.name(),
            RebuildRunState.PAUSED.name());

    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final ElasticsearchIndexManager indexes;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IndexingProperties properties;

    public IndexVersionWriteService(SearchIndexVersionRepository versions,
                                    SearchIndexRebuildRunRepository runs,
                                    ElasticsearchIndexManager indexes, JdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager,
                                    IndexingProperties properties) {
        this.versions = versions;
        this.runs = runs;
        this.indexes = indexes;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    public SearchIndexVersion enable(int versionNumber) {
        requireMutationsEnabled();
        SearchIndexVersion observed = versions.findByVersionNumber(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + versionNumber));
        requireEnableable(observed);
        requireNoPendingTargets(versionNumber);
        // 写入仍关闭，没有实时流量；此时清空物理索引不会与双写竞争
        recreate(observed);
        return transactions.execute(status -> {
            SearchIndexVersion locked = versions.findByVersionNumberForUpdate(versionNumber)
                    .orElseThrow(() -> new IllegalStateException("index version disappeared"));
            requireEnableable(locked);
            locked.startWriteSession(scalar(
                    "SELECT COALESCE(MAX(id), 0) FROM search_index_change_event"));
            return locked;
        });
    }

    public SearchIndexVersion disable(int versionNumber) {
        requireMutationsEnabled();
        return transactions.execute(status -> {
            SearchIndexVersion locked = versions.findByVersionNumberForUpdate(versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown index version: " + versionNumber));
            if (locked.getDeletedAt() != null) {
                throw new IllegalStateException("deleted version cannot change writes");
            }
            requireIdle(versionNumber);
            locked.endWriteSession();
            return locked;
        });
    }

    private void requireEnableable(SearchIndexVersion version) {
        if (version.getDeletedAt() != null) {
            throw new IllegalStateException("deleted version cannot enable writes");
        }
        if (version.isWriteEnabled()) {
            throw new IllegalStateException(
                    "version " + version.getVersionNumber() + " already accepts writes");
        }
        if (!version.isPipelineSupported()) {
            throw new IllegalStateException("version pipeline is unsupported by this deployment");
        }
        if (version.getNeedsAttentionReason() != null) {
            throw new IllegalStateException("version needs attention before enabling writes");
        }
        requireIdle(version.getVersionNumber());
    }

    private void requireIdle(int versionNumber) {
        if (runs.existsByVersionNumberAndStateIn(versionNumber, ACTIVE_RUN_STATES)) {
            throw new IllegalStateException(
                    "version " + versionNumber + " has an active migration");
        }
    }

    /** 上一次写入会话遗留的任务若在清空后才执行，会把旧数据写回新索引。 */
    private void requireNoPendingTargets(int versionNumber) {
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM indexing_job_target
                WHERE target_version=? AND state IN ('PENDING','LEASED','RETRY_WAIT')
                """, Long.class, versionNumber);
        if (pending != null && pending > 0) {
            throw new IllegalStateException(
                    "version " + versionNumber + " still has pending indexing jobs");
        }
    }

    private void recreate(SearchIndexVersion version) {
        EditableIndexConfig config = version.editableConfig();
        try {
            if (config.mappingSchemaVersion() >= 3) {
                indexes.recreateOfflineVersion(version.getPhysicalName(),
                        config.embeddingDimensions(), config.mappingSchemaVersion());
            } else {
                indexes.recreateOfflineVersion(version.getPhysicalName(),
                        config.embeddingDimensions());
            }
        } catch (Exception e) {
            // ES 客户端受检异常统一转为运行时异常，由事务回滚与全局异常处理兜底
            throw new IllegalStateException("failed to recreate offline index "
                    + version.getPhysicalName(), e);
        }
    }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private void requireMutationsEnabled() {
        if (!Boolean.TRUE.equals(properties.management().mutationsEnabled())) {
            throw new IllegalStateException(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
        }
    }
}
