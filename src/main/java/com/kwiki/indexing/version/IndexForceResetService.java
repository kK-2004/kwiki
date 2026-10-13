package com.kwiki.indexing.version;

import com.kk2004.common.exception.BusinessException;
import com.kwiki.graph.GraphDataPurger;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexBootstrap;
import com.kwiki.indexing.search.ElasticsearchIndexManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局索引强制重置：删除全部托管 ES 索引（分块与社区），清空索引版本、重建、校验、审计、
 * 变更事件、索引任务、灰度与知识图谱构建记录，清空图存储数据，然后按首次部署默认配置
 * 重建 v1（别名指向它、写入开启、待迁移）并立即发起全量存量迁移。
 * 解析器、分块器、字段格式等插件，模型配置，Wiki 内容与附件均不受影响。整个流程幂等，失败后可重试。
 */
@Service
public class IndexForceResetService {

    private static final Logger log = LoggerFactory.getLogger(IndexForceResetService.class);

    public static final String CONFIRMATION = "RESET kwiki-chunks";

    /** 清空顺序无关（执行期间关闭外键检查）；只列出与索引、灰度和图谱构建相关的表。 */
    static final List<String> TABLES = List.of(
            "search_index_version_kb_scope", "search_index_rebuild_range", "search_index_rebuild_run",
            "search_index_validation_report", "search_index_audit", "search_index_idempotency",
            "search_index_change_event", "indexing_job_target", "indexing_job", "search_index_version",
            "index_gray_release_kb", "index_gray_release",
            "graph_snapshot_read_lease", "graph_publication", "graph_resource_reference", "graph_snapshot",
            "graph_source_manifest_entry", "graph_source_manifest", "graph_source_epoch",
            "graph_extraction_target", "graph_extraction_result", "graph_entity_registry",
            "graph_build_audit", "graph_build_run", "graph_schedule_trigger", "graph_build_batch",
            "community_index_version");

    private static final String ACTIVE_GRAPH_STATES =
            "'QUEUED','EXTRACTING','PROJECTING','CLUSTERING','SUMMARIZING','INDEXING','VALIDATING'";

    public record Result(List<String> deletedIndices, List<String> purgedGraphTypes,
                         Map<String, Integer> clearedRows, int versionNumber, String physicalName,
                         Long migrationRunId, String migrationError) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ElasticsearchIndexManager indexes;
    private final ObjectProvider<ElasticsearchIndexBootstrap> bootstrap;
    private final ChunkMappingBuilder mappingBuilder;
    private final SearchIndexVersionRepository versions;
    private final IndexMigrationService migrations;
    private final IndexingProperties properties;
    private final ObjectProvider<GraphDataPurger> graphPurger;

    public IndexForceResetService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                  ElasticsearchIndexManager indexes, ObjectProvider<ElasticsearchIndexBootstrap> bootstrap,
                                  ChunkMappingBuilder mappingBuilder, SearchIndexVersionRepository versions,
                                  IndexMigrationService migrations, IndexingProperties properties,
                                  ObjectProvider<GraphDataPurger> graphPurger) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.indexes = indexes;
        this.bootstrap = bootstrap;
        this.mappingBuilder = mappingBuilder;
        this.versions = versions;
        this.migrations = migrations;
        this.properties = properties;
        this.graphPurger = graphPurger;
    }

    public synchronized Result reset(String confirmation, String operator) {
        if (!CONFIRMATION.equals(confirmation == null ? null : confirmation.trim())) {
            throw new BusinessException(400, "确认词不正确，请输入 " + CONFIRMATION);
        }
        if (!Boolean.TRUE.equals(properties.management().mutationsEnabled())) {
            throw new BusinessException(409, SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
        }
        requireNoActiveWork();
        if (!indexes.available()) {
            throw new BusinessException(409, "Elasticsearch 不可用，无法重置索引");
        }
        ElasticsearchIndexBootstrap firstDeployment = bootstrap.getIfAvailable();
        if (firstDeployment == null) {
            throw new BusinessException(409, "首次部署引导未启用（kwiki.indexing.bootstrap.enabled），无法重建 v1");
        }
        log.warn("index force reset started by {}", operator);

        List<String> deleted = deleteManagedIndices();
        GraphDataPurger purger = graphPurger.getIfAvailable();
        List<String> purgedTypes = purger == null ? List.of() : purger.purgeAll();
        Map<String, Integer> cleared = clearTables();

        var config = firstDeployment.firstDeploymentConfig();
        String physicalName = indexes.indexNameFor(1);
        try {
            indexes.createVersionedIndex(physicalName, config.embeddingDimensions(), config.indexSchema());
            String incompatibility = indexes.validateIndex(physicalName, config.embeddingDimensions(),
                    config.indexSchema());
            if (incompatibility != null) {
                throw new IllegalStateException("新建索引的 mapping 不兼容：" + incompatibility);
            }
            String mappingHash = mappingBuilder.mappingHash(config.embeddingDimensions(), config.indexSchema());
            transactions.executeWithoutResult(status -> versions.saveAndFlush(
                    SearchIndexVersion.resetInitial(1, physicalName, config, mappingHash, 0L)));
            indexes.createInitialAlias(physicalName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("index force reset failed while recreating v1; restart the application to bootstrap v1", e);
            throw new BusinessException(500, "索引已清空，但重建 v1 失败：" + e.getMessage()
                    + "。可重试重置，或重启应用由首次部署引导创建 v1");
        }

        Long runId = null;
        String migrationError = null;
        try {
            var start = migrations.migrate(1, operator);
            runId = start.runId();
            if (!start.accepted()) migrationError = start.code();
        } catch (RuntimeException e) {
            migrationError = e.getMessage();
            log.error("index force reset: migration of v1 could not be started", e);
        }
        audit(operator, deleted, purgedTypes, cleared, runId, migrationError);
        log.warn("index force reset completed by {}: deletedIndices={}, graphTypes={}, rows={}, migrationRun={}",
                operator, deleted, purgedTypes, cleared, runId);
        return new Result(deleted, purgedTypes, cleared, 1, physicalName, runId, migrationError);
    }

    private void requireNoActiveWork() {
        List<String> blockers = new ArrayList<>();
        blockers.addAll(jdbc.query(
                "SELECT id, version_number FROM search_index_rebuild_run WHERE state IN ('PENDING','RUNNING') ORDER BY id",
                (rs, n) -> "重建任务 #" + rs.getLong(1) + "（v" + rs.getInt(2) + "）"));
        blockers.addAll(jdbc.query(
                "SELECT id, kb_id FROM graph_build_run WHERE state IN (" + ACTIVE_GRAPH_STATES + ") ORDER BY id",
                (rs, n) -> "图谱构建 #" + rs.getLong(1) + "（知识库 " + rs.getLong(2) + "）"));
        if (!blockers.isEmpty()) {
            throw new BusinessException(409, "有正在运行的任务，请先取消后再重置：" + String.join("、", blockers));
        }
    }

    private List<String> deleteManagedIndices() {
        try {
            List<String> names = indexes.listManagedIndices();
            for (String name : names) {
                indexes.forceDeleteManagedIndex(name);
            }
            return names;
        } catch (Exception e) {
            throw new BusinessException(500, "删除 Elasticsearch 索引失败：" + e.getMessage());
        }
    }

    private Map<String, Integer> clearTables() {
        Map<String, Integer> cleared = new LinkedHashMap<>();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : TABLES) {
                    cleared.put(table, jdbc.update("DELETE FROM " + table));
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
        return cleared;
    }

    private void audit(String operator, List<String> deleted, List<String> purgedTypes,
                       Map<String, Integer> cleared, Long runId, String migrationError) {
        int rows = cleared.values().stream().mapToInt(Integer::intValue).sum();
        String result = ("deletedIndices=" + deleted.size() + " graphTypes=" + purgedTypes.size()
                + " clearedRows=" + rows + " recreated=v1");
        String summary = migrationError == null ? null
                : ("migration not started: " + migrationError);
        jdbc.update("""
                INSERT INTO search_index_audit (action, target_version, run_id, operator, config_revision,
                    prior_state, result_state, outcome, error_summary)
                VALUES ('FORCE_RESET', 1, ?, ?, 1, ?, ?, ?, ?)
                """, runId, operator, truncate(String.join(",", deleted), 300), truncate(result, 300),
                migrationError == null ? "SUCCESS" : "FAILURE", truncate(summary, 1000));
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }
}
