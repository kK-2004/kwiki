package com.kwiki.graph.persistence;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图管理查询：批次/子任务、调度触发、发布配对、快照与社区版本的只读视图。
 * 只暴露允许披露的字段；不回显凭据、正文或隐私实体名。
 */
@Service
public class GraphAdminQueryService {

    private final JdbcOperations jdbc;

    public GraphAdminQueryService(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批次列表：双 ES 版本徽标数据与聚合状态。已删除且外部数据清理完成的批次隐藏；
     * 清理未完成（PENDING/FAILED）的仍显示，便于再次删除重试清理。
     */
    public List<Map<String, Object>> batches() {
        return camel(jdbc.queryForList("SELECT id, scope_kind, chunk_index_version, "
                + "community_index_version, state, auto_publish, requested_by, "
                + "schedule_date, failure_summary, created_at, "
                + "extractor, community_detector, summarizer, "
                + "deleted_at, cleanup_state, cleanup_summary "
                + "FROM graph_build_batch "
                + "WHERE deleted_at IS NULL OR cleanup_state <> 'DONE' "
                + "ORDER BY id DESC LIMIT 100"));
    }

    /** 批次的按库子任务：阶段、版本对、图版本与计数。 */
    public List<Map<String, Object>> runsOfBatch(long batchId) {
        return camel(jdbc.queryForList("SELECT id, kb_id, chunk_index_version, "
                + "community_index_version, community_physical_index, graph_version, "
                + "state, stage, entity_count, relation_count, source_count, "
                + "community_count, error_code, error_summary, started_at, completed_at, "
                + "extractor, community_detector, summarizer, "
                + "sources_total, sources_extracted, extraction_cache_hits, "
                + "communities_total, communities_summarized "
                + "FROM graph_build_run WHERE batch_id = ? ORDER BY id", batchId));
    }

    /** 调度触发记录：当日 02:00 状态、漏跑补偿与 SKIPPED_ACTIVE 关联。 */
    public List<Map<String, Object>> scheduleTriggers() {
        return camel(jdbc.queryForList("SELECT schedule_date, status, linked_batch_id, created_at "
                + "FROM graph_schedule_trigger ORDER BY schedule_date DESC LIMIT 60"));
    }

    /** 各库发布配对：CHUNK 版本 → 活动快照与 graphVersion/community 版本。 */
    public List<Map<String, Object>> publications() {
        return camel(jdbc.queryForList("SELECT p.kb_id, p.chunk_index_version, p.active_snapshot_id, "
                + "s.graph_version, s.community_index_version, s.community_physical_index, "
                + "s.state AS snapshot_state, p.content_epoch, p.security_epoch, p.published_at "
                + "FROM graph_publication p LEFT JOIN graph_snapshot s "
                + "ON s.id = p.active_snapshot_id ORDER BY p.kb_id, p.chunk_index_version"));
    }

    /** 快照清单（近期）：版本三元组、epoch 与校验状态。 */
    public List<Map<String, Object>> snapshots() {
        return camel(jdbc.queryForList("SELECT id, kb_id, graph_version, chunk_index_version, "
                + "community_index_version, community_physical_index, state, "
                + "entity_count, relation_count, community_count, content_epoch, "
                + "security_epoch, sealed_at, retired_at, created_at, "
                + "extractor, community_detector, summarizer, "
                // 退役快照的外部数据是否已清理完：资源已登记且全部删除
                + "(state = 'DELETING' "
                + " AND EXISTS (SELECT 1 FROM graph_resource_reference g WHERE g.snapshot_id = graph_snapshot.id) "
                + " AND NOT EXISTS (SELECT 1 FROM graph_resource_reference g "
                + "                 WHERE g.snapshot_id = graph_snapshot.id AND g.cleanup_state <> 'DELETED')"
                + ") AS cleaned "
                + "FROM graph_snapshot WHERE run_id NOT IN (SELECT r.id FROM graph_build_run r "
                + "JOIN graph_build_batch b ON b.id = r.batch_id WHERE b.deleted_at IS NOT NULL) "
                + "ORDER BY id DESC LIMIT 100"));
    }

    /** COMMUNITY 索引版本视图：版本、映射代际、绑定批次与各库物理索引。 */
    public List<Map<String, Object>> communityVersions() {
        List<Map<String, Object>> versions = jdbc.queryForList(
                "SELECT version_number, batch_id, mapping_schema_version, config_revision, "
                        + "state, created_at FROM community_index_version "
                        + "ORDER BY version_number DESC LIMIT 100");
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> version : versions) {
            Map<String, Object> view = camel(version);
            view.put("physicalIndexes", camel(jdbc.queryForList(
                    "SELECT kb_id, community_physical_index, graph_version, state "
                            + "FROM graph_build_run WHERE community_index_version = ? "
                            + "ORDER BY kb_id", version.get("version_number"))));
            result.add(view);
        }
        return result;
    }

    /** 快照校验报告（持久化 JSON 反解后的原始文本）。 */
    public Map<String, Object> validationReport(long snapshotId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotId", snapshotId);
        result.put("validationJson", jdbc.queryForObject(
                "SELECT validation_json FROM graph_snapshot WHERE id = ?",
                String.class, snapshotId));
        return result;
    }

    /** 图构建审计记录。 */
    public List<Map<String, Object>> audits() {
        return camel(jdbc.queryForList("SELECT id, batch_id, run_id, action, idempotency_key, "
                + "operator, result_state, summary, created_at FROM graph_build_audit "
                + "ORDER BY id DESC LIMIT 100"));
    }

    /** 管理端契约为驼峰字段名（如 chunkIndexVersion）；JDBC 结果的键是下划线列名，统一转换。 */
    static List<Map<String, Object>> camel(List<Map<String, Object>> rows) {
        return rows.stream().map(GraphAdminQueryService::camel).toList();
    }

    static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> converted = new LinkedHashMap<>();
        row.forEach((key, value) -> converted.put(camelKey(key), timestamp(value)));
        return converted;
    }

    /**
     * DATETIME 列按 UTC 存储（连接时区 UTC）。驱动返回的 LocalDateTime 是 UTC 墙上时间，
     * 转为 Instant 后由全局 Jackson 配置按展示时区输出，与其他接口一致；DATE 等其余类型原样返回。
     */
    static Object timestamp(Object value) {
        if (value instanceof java.time.LocalDateTime local) {
            return local.toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return value;
    }

    static String camelKey(String column) {
        StringBuilder out = new StringBuilder(column.length());
        boolean upper = false;
        for (char ch : column.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (ch == '_') {
                upper = out.length() > 0;
            } else {
                out.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return out.toString();
    }
}
