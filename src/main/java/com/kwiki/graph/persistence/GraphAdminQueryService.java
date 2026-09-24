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

    /** 批次列表：双 ES 版本徽标数据与聚合状态。 */
    public List<Map<String, Object>> batches() {
        return jdbc.queryForList("SELECT id, scope_kind, chunk_index_version, "
                + "community_index_version, state, auto_publish, requested_by, "
                + "schedule_date, failure_summary, created_at "
                + "FROM graph_build_batch ORDER BY id DESC LIMIT 100");
    }

    /** 批次的按库子任务：阶段、版本对、图版本与计数。 */
    public List<Map<String, Object>> runsOfBatch(long batchId) {
        return jdbc.queryForList("SELECT id, kb_id, chunk_index_version, "
                + "community_index_version, community_physical_index, graph_version, "
                + "state, stage, entity_count, relation_count, source_count, "
                + "community_count, error_code, error_summary, started_at, completed_at "
                + "FROM graph_build_run WHERE batch_id = ? ORDER BY id", batchId);
    }

    /** 调度触发记录：当日 02:00 状态、漏跑补偿与 SKIPPED_ACTIVE 关联。 */
    public List<Map<String, Object>> scheduleTriggers() {
        return jdbc.queryForList("SELECT schedule_date, status, linked_batch_id, created_at "
                + "FROM graph_schedule_trigger ORDER BY schedule_date DESC LIMIT 60");
    }

    /** 各库发布配对：CHUNK 版本 → 活动快照与 graphVersion/community 版本。 */
    public List<Map<String, Object>> publications() {
        return jdbc.queryForList("SELECT p.kb_id, p.chunk_index_version, p.active_snapshot_id, "
                + "s.graph_version, s.community_index_version, s.community_physical_index, "
                + "s.state AS snapshot_state, p.content_epoch, p.security_epoch, p.published_at "
                + "FROM graph_publication p LEFT JOIN graph_snapshot s "
                + "ON s.id = p.active_snapshot_id ORDER BY p.kb_id, p.chunk_index_version");
    }

    /** 快照清单（近期）：版本三元组、epoch 与校验状态。 */
    public List<Map<String, Object>> snapshots() {
        return jdbc.queryForList("SELECT id, kb_id, graph_version, chunk_index_version, "
                + "community_index_version, community_physical_index, state, "
                + "entity_count, relation_count, community_count, content_epoch, "
                + "security_epoch, sealed_at, retired_at, created_at "
                + "FROM graph_snapshot ORDER BY id DESC LIMIT 100");
    }

    /** COMMUNITY 索引版本视图：版本、映射代际、绑定批次与各库物理索引。 */
    public List<Map<String, Object>> communityVersions() {
        List<Map<String, Object>> versions = jdbc.queryForList(
                "SELECT version_number, batch_id, mapping_schema_version, config_revision, "
                        + "state, created_at FROM community_index_version "
                        + "ORDER BY version_number DESC LIMIT 100");
        for (Map<String, Object> version : versions) {
            version.put("physicalIndexes", jdbc.queryForList(
                    "SELECT kb_id, community_physical_index, graph_version, state "
                            + "FROM graph_build_run WHERE community_index_version = ? "
                            + "ORDER BY kb_id", version.get("version_number")));
        }
        return versions;
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
        return jdbc.queryForList("SELECT id, batch_id, run_id, action, idempotency_key, "
                + "operator, result_state, summary, created_at FROM graph_build_audit "
                + "ORDER BY id DESC LIMIT 100");
    }
}
