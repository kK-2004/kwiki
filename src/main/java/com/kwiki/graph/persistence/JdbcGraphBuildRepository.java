package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 使用 MySQL 行锁、条件更新和唯一键实现图任务的幂等与 fencing。 */
@Repository
public class JdbcGraphBuildRepository implements GraphBuildRepository {

    private static final List<String> ACTIVE_STATES = List.of(
            "QUEUED", "EXTRACTING", "PROJECTING", "CLUSTERING", "SUMMARIZING",
            "INDEXING", "VALIDATING", "WAITING_FOR_CHUNKS", "UNSUPPORTED", "NEEDS_ATTENTION");

    private final JdbcTemplate jdbc;

    public JdbcGraphBuildRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> findBatchByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT id FROM graph_build_batch WHERE idempotency_key = ?",
                rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(), idempotencyKey);
    }

    @Override
    public Optional<Long> findScheduledBatch(LocalDate scheduleDate) {
        return jdbc.query("SELECT id FROM graph_build_batch WHERE schedule_date = ?",
                rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(), scheduleDate);
    }

    @Override
    @Transactional
    public long allocateCommunityIndexVersion(String physicalNamePattern, int mappingSchemaVersion,
                                               long configRevision) {
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM community_index_version FOR UPDATE",
                Long.class);
        long next = (current == null ? 0 : current) + 1;
        jdbc.update("INSERT INTO community_index_version "
                        + "(version_number, physical_name_pattern, mapping_schema_version, config_revision) "
                        + "VALUES (?, ?, ?, ?)",
                next, physicalNamePattern, mappingSchemaVersion, configRevision);
        return next;
    }

    @Override
    @Transactional
    public long allocateGraphVersion(long kbId) {
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(graph_version), 0) FROM graph_build_run WHERE kb_id = ? FOR UPDATE",
                Long.class, kbId);
        return (current == null ? 0 : current) + 1;
    }

    @Override
    @Transactional
    public long createBatch(GraphBuildBatchCommand command) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO graph_build_batch "
                            + "(idempotency_key, scope_kind, requested_kb_ids_json, chunk_index_version, "
                            + "chunk_physical_index, community_index_version, auto_publish, requested_by, "
                            + "schedule_date, extractor, community_detector, summarizer) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, command.idempotencyKey());
            statement.setString(2, command.scopeKind());
            statement.setString(3, command.requestedKnowledgeBaseIdsJson());
            statement.setInt(4, command.chunkIndexVersion());
            statement.setString(5, command.chunkPhysicalIndex());
            statement.setLong(6, command.communityIndexVersion());
            statement.setBoolean(7, command.autoPublish());
            statement.setString(8, command.requestedBy());
            if (command.scheduleDate() == null) statement.setNull(9, java.sql.Types.DATE);
            else statement.setObject(9, command.scheduleDate());
            statement.setString(10, command.extractor());
            statement.setString(11, command.communityDetector());
            statement.setString(12, command.summarizer());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("图批次插入未返回主键");
        return key.longValue();
    }

    @Override
    @Transactional
    public long createRun(GraphBuildRunCommand command) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO graph_build_run "
                            + "(batch_id, kb_id, chunk_index_version, chunk_physical_index, "
                            + "community_index_version, community_physical_index, graph_version, "
                            + "chunk_index_schema, entity_linking_version, content_epoch, security_epoch, "
                            + "extractor, community_detector, summarizer) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, command.batchId());
            statement.setLong(2, command.kbId());
            statement.setInt(3, command.chunkIndexVersion());
            statement.setString(4, command.chunkPhysicalIndex());
            statement.setLong(5, command.communityIndexVersion());
            statement.setString(6, command.communityPhysicalIndex());
            statement.setLong(7, command.graphVersion());
            statement.setString(8, command.chunkIndexSchema());
            statement.setString(9, command.entityLinkingVersion());
            statement.setLong(10, command.contentEpoch());
            statement.setLong(11, command.securityEpoch());
            statement.setString(12, command.extractor());
            statement.setString(13, command.communityDetector());
            statement.setString(14, command.summarizer());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("图构建子任务插入未返回主键");
        return key.longValue();
    }

    @Override
    public void bindCommunityVersionToBatch(long communityIndexVersion, long batchId) {
        int updated = jdbc.update("UPDATE community_index_version SET batch_id = ? "
                        + "WHERE version_number = ? AND batch_id IS NULL", batchId, communityIndexVersion);
        if (updated != 1) {
            throw new IllegalStateException("社区版本已绑定其他批次: " + communityIndexVersion);
        }
    }

    @Override
    @Transactional
    public Optional<GraphBuildRunRecord> findActiveRunForUpdate(long kbId) {
        String placeholders = "?".repeat(ACTIVE_STATES.size()).replace("?", "?,");
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        String sql = "SELECT id, batch_id, kb_id, chunk_index_version, chunk_physical_index, "
                + "community_index_version, community_physical_index, graph_version, "
                + "chunk_index_schema, entity_linking_version, content_epoch, security_epoch, "
                + "state, stage, "
                + "fencing_token, lease_owner, lease_expires_at, event_watermark, "
                + "extractor, community_detector, summarizer "
                + "FROM graph_build_run WHERE kb_id = ? AND state IN (" + placeholders + ") "
                + "ORDER BY id DESC LIMIT 1 FOR UPDATE";
        Object[] args = new Object[1 + ACTIVE_STATES.size()];
        args[0] = kbId;
        for (int i = 0; i < ACTIVE_STATES.size(); i++) args[i + 1] = ACTIVE_STATES.get(i);
        return jdbc.query(sql, rs -> rs.next() ? Optional.of(mapRun(rs)) : Optional.empty(), args);
    }

    @Override
    public boolean hasActiveRunReferencingChunkIndex(int chunkIndexVersion) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_build_run WHERE chunk_index_version = ? "
                        + "AND state IN (" + activeStateSql() + ")",
                Long.class, chunkIndexVersion);
        return count != null && count > 0;
    }

    @Override
    public boolean acquireLease(long runId, String owner, Duration leaseDuration, long expectedFencingToken) {
        int updated = jdbc.update("UPDATE graph_build_run SET lease_owner = ?, "
                        + "lease_expires_at = ?, fencing_token = fencing_token + 1 "
                + "WHERE id = ? AND fencing_token = ? AND state IN (" + activeStateSql() + ") "
                        + "AND (lease_owner IS NULL OR lease_expires_at < CURRENT_TIMESTAMP(6))",
                owner, Timestamp.from(Instant.now().plus(leaseDuration)), runId,
                expectedFencingToken);
        return updated == 1;
    }

    @Override
    public boolean renewLease(long runId, String owner, long fencingToken, Duration leaseDuration) {
        return jdbc.update("UPDATE graph_build_run SET lease_expires_at = ? "
                        + "WHERE id = ? AND lease_owner = ? AND fencing_token = ?",
                Timestamp.from(Instant.now().plus(leaseDuration)), runId, owner, fencingToken) == 1;
    }

    @Override
    public boolean checkpoint(long runId, String owner, long fencingToken,
                             GraphBuildState state, GraphBuildStage stage, long eventWatermark) {
        return jdbc.update("UPDATE graph_build_run SET state = ?, stage = ?, event_watermark = ? "
                        + "WHERE id = ? AND lease_owner = ? AND fencing_token = ?",
                state.name(), stage.name(), eventWatermark, runId, owner, fencingToken) == 1;
    }

    @Override
    public Optional<GraphPublicationRecord> findPublication(long kbId, int chunkIndexVersion) {
        return jdbc.query("SELECT kb_id, chunk_index_version, active_snapshot_id, content_epoch, security_epoch "
                        + "FROM graph_publication WHERE kb_id = ? AND chunk_index_version = ?",
                rs -> rs.next() ? Optional.of(new GraphPublicationRecord(rs.getLong(1), rs.getInt(2),
                        (Long) rs.getObject(3), rs.getLong(4), rs.getLong(5))) : Optional.empty(),
                kbId, chunkIndexVersion);
    }

    @Override
    public boolean compareAndSetPublication(long kbId, int chunkIndexVersion, Long expectedSnapshotId,
                                            long nextSnapshotId, long contentEpoch, long securityEpoch,
                                            Instant publishedAt) {
        jdbc.update("INSERT IGNORE INTO graph_publication "
                        + "(kb_id, chunk_index_version, content_epoch, security_epoch) VALUES (?, ?, ?, ?)",
                kbId, chunkIndexVersion, contentEpoch, securityEpoch);
        String expected = expectedSnapshotId == null ? "active_snapshot_id IS NULL"
                : "active_snapshot_id = " + expectedSnapshotId;
        // 只按指针 CAS：epoch 新鲜度已由调用方在同一事务内持有 graph_source_epoch
        // 行锁后复核。行上存的是上次发布时的 epoch，内容变化后必然与当前值不同，
        // 不能再拿来做条件，否则一旦 epoch 推进就再也无法重新发布或回滚。
        return jdbc.update("UPDATE graph_publication SET active_snapshot_id = ?, expected_snapshot_id = NULL, "
                        + "content_epoch = ?, security_epoch = ?, published_at = ? "
                        + "WHERE kb_id = ? AND chunk_index_version = ? AND " + expected,
                nextSnapshotId, contentEpoch, securityEpoch, Timestamp.from(publishedAt),
                kbId, chunkIndexVersion) == 1;
    }

    @Override
    public boolean sealSnapshotReady(long snapshotId, String validationJson) {
        return jdbc.update("UPDATE graph_snapshot SET validation_json = ?, state = 'READY', "
                        + "sealed_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE id = ? AND state = 'BUILDING'",
                validationJson, snapshotId) == 1;
    }

    @Override
    public boolean persistValidationReport(long snapshotId, String validationJson) {
        return jdbc.update("UPDATE graph_snapshot SET validation_json = ? "
                        + "WHERE id = ? AND state = 'BUILDING'",
                validationJson, snapshotId) == 1;
    }

    @Override
    public Optional<String> findSnapshotValidationJson(long snapshotId) {
        return jdbc.query("SELECT validation_json FROM graph_snapshot WHERE id = ?",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(),
                snapshotId);
    }

    @Override
    public boolean findBatchAutoPublishForRun(long runId) {
        Boolean autoPublish = jdbc.queryForObject(
                "SELECT b.auto_publish FROM graph_build_run r "
                        + "JOIN graph_build_batch b ON b.id = r.batch_id WHERE r.id = ?",
                Boolean.class, runId);
        return Boolean.TRUE.equals(autoPublish);
    }

    @Override
    public Optional<GraphSnapshotEntry> findActivePublicationSnapshot(long kbId,
                                                                      int chunkIndexVersion) {
        return jdbc.query("SELECT s.id, s.kb_id, s.graph_version, s.chunk_index_version, "
                        + "s.community_index_version, s.chunk_physical_index, "
                        + "s.community_physical_index, s.entity_linking_version, "
                        + "s.source_manifest_hash, s.content_epoch, s.security_epoch, s.state "
                        + "FROM graph_publication p "
                        + "JOIN graph_snapshot s ON s.id = p.active_snapshot_id "
                        + "WHERE p.kb_id = ? AND p.chunk_index_version = ?",
                rs -> rs.next() ? Optional.of(new GraphSnapshotEntry(
                        new com.kwiki.graph.GraphSnapshot(rs.getLong("id"), rs.getLong("kb_id"),
                                rs.getLong("graph_version"), rs.getLong("chunk_index_version"),
                                rs.getLong("community_index_version"),
                                rs.getString("chunk_physical_index"),
                                rs.getString("community_physical_index"),
                                rs.getString("entity_linking_version"),
                                rs.getString("source_manifest_hash"),
                                rs.getLong("content_epoch"), rs.getLong("security_epoch")),
                        GraphSnapshotState.valueOf(rs.getString("state")))) : Optional.empty(),
                kbId, chunkIndexVersion);
    }

    @Override
    public long acquireReadLease(long snapshotId, Instant expiresAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO graph_snapshot_read_lease (snapshot_id, expires_at) "
                            + "SELECT id, ? FROM graph_snapshot "
                            + "WHERE id = ? AND state IN ('READY','PUBLISHED')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setTimestamp(1, Timestamp.from(expiresAt));
            statement.setLong(2, snapshotId);
            return statement;
        }, keys);
        if (inserted != 1) {
            return 0;
        }
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    @Override
    public boolean releaseReadLease(long leaseId) {
        return jdbc.update("DELETE FROM graph_snapshot_read_lease WHERE id = ?", leaseId) == 1;
    }

    @Override
    public boolean hasActiveReadLeases(long snapshotId) {
        Boolean active = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM graph_snapshot_read_lease "
                        + "WHERE snapshot_id = ? AND expires_at > CURRENT_TIMESTAMP(6))",
                Boolean.class, snapshotId);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public Optional<GraphSnapshotEntry> findSnapshotById(long snapshotId) {
        return jdbc.query("SELECT id, kb_id, graph_version, chunk_index_version, "
                        + "community_index_version, chunk_physical_index, "
                        + "community_physical_index, entity_linking_version, "
                        + "source_manifest_hash, content_epoch, security_epoch, state "
                        + "FROM graph_snapshot WHERE id = ?",
                rs -> rs.next() ? Optional.of(new GraphSnapshotEntry(
                        new com.kwiki.graph.GraphSnapshot(rs.getLong("id"), rs.getLong("kb_id"),
                                rs.getLong("graph_version"), rs.getLong("chunk_index_version"),
                                rs.getLong("community_index_version"),
                                rs.getString("chunk_physical_index"),
                                rs.getString("community_physical_index"),
                                rs.getString("entity_linking_version"),
                                rs.getString("source_manifest_hash"),
                                rs.getLong("content_epoch"), rs.getLong("security_epoch")),
                        GraphSnapshotState.valueOf(rs.getString("state")))) : Optional.empty(),
                snapshotId);
    }

    @Override
    public boolean isActivePublicationTarget(long snapshotId) {
        Boolean active = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM graph_publication WHERE active_snapshot_id = ?)",
                Boolean.class, snapshotId);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public boolean hasActiveBuildForSnapshot(long snapshotId) {
        Boolean active = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM graph_build_run r JOIN graph_snapshot s "
                        + "ON s.run_id = r.id WHERE s.id = ? AND r.state IN ("
                        + activeStateSql() + "))",
                Boolean.class, snapshotId);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public List<Long> findRecentSnapshotIds(long kbId, int chunkIndexVersion, int limit) {
        return jdbc.queryForList("SELECT id FROM graph_snapshot "
                        + "WHERE kb_id = ? AND chunk_index_version = ? "
                        + "ORDER BY id DESC LIMIT " + limit,
                Long.class, kbId, chunkIndexVersion);
    }

    @Override
    public boolean markSnapshotDeleting(long snapshotId) {
        return jdbc.update("UPDATE graph_snapshot SET state = 'DELETING', "
                        + "retired_at = COALESCE(retired_at, CURRENT_TIMESTAMP(6)) "
                        + "WHERE id = ? AND state IN ('READY','PUBLISHED','RETIRED')",
                snapshotId) == 1;
    }

    @Override
    public Optional<Instant> findSnapshotRetiredAt(long snapshotId) {
        return jdbc.query("SELECT retired_at FROM graph_snapshot WHERE id = ?",
                rs -> rs.next()
                        ? Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant)
                        : Optional.empty(),
                snapshotId);
    }

    @Override
    public List<GraphResourceReferenceRecord> findPendingResourceReferences(long snapshotId) {
        return jdbc.query("SELECT id, resource_kind, resource_identity, cleanup_state, deleted_at "
                        + "FROM graph_resource_reference "
                        + "WHERE snapshot_id = ? AND cleanup_state <> 'DELETED' "
                        + "ORDER BY resource_kind, resource_identity, id",
                (rs, row) -> new GraphResourceReferenceRecord(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
                snapshotId);
    }

    @Override
    public boolean markResourceDeleting(long resourceId) {
        return jdbc.update("UPDATE graph_resource_reference SET cleanup_state = 'DELETING' "
                + "WHERE id = ? AND cleanup_state <> 'DELETED'", resourceId) == 1;
    }

    @Override
    public boolean markResourceDeleted(long resourceId) {
        return jdbc.update("UPDATE graph_resource_reference SET cleanup_state = 'DELETED', "
                + "deleted_at = CURRENT_TIMESTAMP(6) "
                + "WHERE id = ? AND cleanup_state <> 'DELETED'", resourceId) == 1;
    }

    @Override
    public void ensureSnapshotResources(long snapshotId) {
        jdbc.update("INSERT IGNORE INTO graph_resource_reference "
                        + "(snapshot_id, resource_kind, resource_identity) "
                        + "SELECT id, '" + GraphArtifactCleaner.KIND_GRAPH_VERSION + "', "
                        + "CONCAT(kb_id, ':', graph_version) FROM graph_snapshot WHERE id = ?",
                snapshotId);
        jdbc.update("INSERT IGNORE INTO graph_resource_reference "
                        + "(snapshot_id, resource_kind, resource_identity) "
                        + "SELECT id, '" + GraphArtifactCleaner.KIND_COMMUNITY_INDEX + "', "
                        + "community_physical_index FROM graph_snapshot "
                        + "WHERE id = ? AND community_physical_index IS NOT NULL "
                        + "AND community_physical_index <> ''",
                snapshotId);
    }

    @Override
    public List<Long> findSnapshotsAwaitingCleanup() {
        return jdbc.queryForList("SELECT s.id FROM graph_snapshot s "
                + "JOIN graph_build_run r ON r.id = s.run_id "
                + "JOIN graph_build_batch b ON b.id = r.batch_id "
                + "WHERE s.state = 'DELETING' AND b.deleted_at IS NULL "
                + "AND (NOT EXISTS (SELECT 1 FROM graph_resource_reference g WHERE g.snapshot_id = s.id) "
                + "  OR EXISTS (SELECT 1 FROM graph_resource_reference g "
                + "             WHERE g.snapshot_id = s.id AND g.cleanup_state <> 'DELETED')) "
                + "ORDER BY s.id", Long.class);
    }

    @Override
    public boolean markResourceFailed(long resourceId, String errorSummary) {
        return jdbc.update("UPDATE graph_resource_reference SET cleanup_state = 'FAILED', "
                        + "last_error_summary = ? WHERE id = ? AND cleanup_state <> 'DELETED'",
                errorSummary, resourceId) == 1;
    }

    @Override
    public List<com.kwiki.graph.GraphResourceId> findManifestResourceIds(long snapshotId) {
        return jdbc.query("SELECT DISTINCT e.resource_type, e.resource_id "
                        + "FROM graph_source_manifest_entry e "
                        + "JOIN graph_snapshot s ON s.source_manifest_id = e.manifest_id "
                        + "WHERE s.id = ? ORDER BY e.resource_type, e.resource_id",
                (rs, row) -> new com.kwiki.graph.GraphResourceId(
                        rs.getString(1), rs.getLong(2)), snapshotId);
    }

    @Override
    public boolean insertScheduleTrigger(LocalDate scheduleDate, String status,
                                         Long linkedBatchId) {
        return jdbc.update("INSERT IGNORE INTO graph_schedule_trigger "
                        + "(schedule_date, status, linked_batch_id) VALUES (?, ?, ?)",
                scheduleDate, status, linkedBatchId) == 1;
    }

    @Override
    public Optional<GraphScheduleTriggerRecord> findScheduleTrigger(LocalDate scheduleDate) {
        return jdbc.query("SELECT schedule_date, status, linked_batch_id "
                        + "FROM graph_schedule_trigger WHERE schedule_date = ?",
                rs -> rs.next() ? Optional.of(new GraphScheduleTriggerRecord(
                        rs.getDate(1).toLocalDate(), rs.getString(2),
                        (Long) rs.getObject(3))) : Optional.empty(),
                scheduleDate);
    }

    @Override
    public Optional<Long> findActiveBatchId() {
        return jdbc.query("SELECT id FROM graph_build_batch "
                        + "WHERE state IN ('QUEUED','RUNNING') ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty());
    }

    @Override
    public boolean requeueRun(long runId) {
        // 已删除批次的子任务是墓碑，不能再被重试拉起
        // 模型调用统计只反映本次执行：重新排队时清零
        boolean requeued = jdbc.update("UPDATE graph_build_run SET state = 'QUEUED', "
                        + "stage = 'QUEUED', error_code = NULL, error_summary = NULL, "
                        + "model_retries = 0, model_timeouts = 0, " + RELEASE_LEASE
                        + "WHERE id = ? AND state IN ('FAILED','CANCELLED','STALE') "
                        + "AND batch_id NOT IN (SELECT id FROM graph_build_batch WHERE deleted_at IS NOT NULL)",
                runId) == 1;
        if (requeued) {
            // 批次重新有未结束的子任务：从结束态回到排队中，开始执行时再置为进行中
            jdbc.update("UPDATE graph_build_batch SET state = 'QUEUED' "
                    + "WHERE id = (SELECT batch_id FROM graph_build_run WHERE id = ?) "
                    + "AND state IN ('READY','PARTIAL_FAILED','FAILED','CANCELLED','STALE')", runId);
        }
        return requeued;
    }

    @Override
    public void markBatchRunning(long batchId) {
        jdbc.update("UPDATE graph_build_batch SET state = 'RUNNING' WHERE id = ? AND state <> 'RUNNING'",
                batchId);
    }

    @Override
    public boolean cancelRun(long runId) {
        // 阶段保持在取消时所处的位置（执行器取消后直接退出，没有真正的清理阶段）
        // 同时释放租约：仍在跑的旧执行者续租失败后自行退出，重试后可立即被重新领取
        boolean cancelled = jdbc.update("UPDATE graph_build_run SET state = 'CANCELLED', "
                        + "completed_at = CURRENT_TIMESTAMP(6), " + RELEASE_LEASE
                        + "WHERE id = ? AND state IN (" + activeStateSql() + ")",
                runId) == 1;
        if (cancelled) {
            // 执行器被取消时不写任何状态，批次收尾只能在这里做，否则批次会一直停在排队中/进行中
            Long batchId = jdbc.queryForObject(
                    "SELECT batch_id FROM graph_build_run WHERE id = ?", Long.class, runId);
            if (batchId != null) {
                settleBatch(batchId);
            }
        }
        return cancelled;
    }

    @Override
    public void audit(String action, String idempotencyKey, String operator,
                      Long batchId, Long runId, String resultState, String summary) {
        jdbc.update("INSERT INTO graph_build_audit "
                        + "(action, idempotency_key, operator, batch_id, run_id, "
                        + "result_state, summary) VALUES (?, ?, ?, ?, ?, ?, ?)",
                action, idempotencyKey, operator, batchId, runId, resultState, summary);
    }

    @Override
    public Optional<String> findCompletedAuditSummary(String idempotencyKey) {
        return jdbc.query("SELECT summary FROM graph_build_audit "
                        + "WHERE idempotency_key = ? AND result_state = 'COMPLETED' "
                        + "ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(),
                idempotencyKey);
    }

    // ---- 执行器扩展实现 ----

    /** 子任务终态集合：批次收尾只在全部子任务进入终态后进行。 */
    /**
     * 结束或重新排队时释放租约：领取条件要求「无持有者或租约已过期」，
     * 不释放的话重试的子任务要等已取消 / 已失败那次执行的租约自然过期（默认 2 分钟）才能再被领取。
     * fencing_token 不变：重新领取时会 +1，旧持有者的写入仍被隔离。
     */
    static final String RELEASE_LEASE = "lease_owner = NULL, lease_expires_at = NULL ";

    private static final String TERMINAL_STATES =
            "'READY','PUBLISHED','FAILED','CANCELLED','STALE'";

    @Override
    public List<GraphBuildRunRecord> findClaimableRuns(Instant now, int limit) {
        String sql = "SELECT id, batch_id, kb_id, chunk_index_version, chunk_physical_index, "
                + "community_index_version, community_physical_index, graph_version, "
                + "chunk_index_schema, entity_linking_version, content_epoch, security_epoch, "
                + "state, stage, fencing_token, lease_owner, lease_expires_at, event_watermark, "
                + "extractor, community_detector, summarizer "
                + "FROM graph_build_run WHERE state = 'QUEUED' "
                + "OR (state IN (" + activeStateSql() + ") AND lease_expires_at < ?) "
                + "ORDER BY batch_id, id LIMIT ?";
        return jdbc.query(sql, (rs, row) -> mapRun(rs), Timestamp.from(now), limit);
    }

    @Override
    public int countLiveRuns(Instant now) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_build_run WHERE state IN (" + activeStateSql() + ") "
                        + "AND lease_expires_at >= ?",
                Long.class, Timestamp.from(now));
        return count == null ? 0 : count.intValue();
    }

    @Override
    public boolean recordModelAttemptFailure(long runId, String owner, long fencingToken,
                                             int retried, int timedOut) {
        return jdbc.update("UPDATE graph_build_run SET model_retries = model_retries + ?, "
                        + "model_timeouts = model_timeouts + ? "
                        + "WHERE id = ? AND lease_owner = ? AND fencing_token = ?",
                retried, timedOut, runId, owner, fencingToken) == 1;
    }

    @Override
    public boolean updateProgress(long runId, String owner, long fencingToken,
                                  com.kwiki.graph.build.RunProgress progress) {
        if (progress == null) {
            progress = com.kwiki.graph.build.RunProgress.EMPTY;
        }
        return jdbc.update("UPDATE graph_build_run SET sources_total = ?, sources_extracted = ?, "
                        + "extraction_cache_hits = ?, communities_total = ?, communities_summarized = ? "
                        + "WHERE id = ? AND lease_owner = ? AND fencing_token = ?",
                progress.sourcesTotal(), progress.sourcesExtracted(), progress.extractionCacheHits(),
                progress.communitiesTotal(), progress.communitiesSummarized(),
                runId, owner, fencingToken) == 1;
    }

    @Override
    public boolean failRun(long runId, String owner, long fencingToken,
                           String errorCode, String errorSummary) {
        return jdbc.update("UPDATE graph_build_run SET state = 'FAILED', error_code = ?, "
                        + "error_summary = ?, completed_at = CURRENT_TIMESTAMP(6), " + RELEASE_LEASE
                        + "WHERE id = ? AND lease_owner = ? AND fencing_token = ?",
                errorCode, errorSummary, runId, owner, fencingToken) == 1;
    }

    @Override
    public boolean markStale(long runId, String owner, long fencingToken) {
        return jdbc.update("UPDATE graph_build_run SET state = 'STALE', "
                        + "completed_at = CURRENT_TIMESTAMP(6), " + RELEASE_LEASE
                        + "WHERE id = ? AND lease_owner = ? AND fencing_token = ?",
                runId, owner, fencingToken) == 1;
    }

    @Override
    @Transactional
    public void settleBatch(long batchId) {
        Long unfinished = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_build_run WHERE batch_id = ? AND state NOT IN ("
                        + TERMINAL_STATES + ")", Long.class, batchId);
        if (unfinished == null || unfinished > 0) {
            return;
        }
        Long failing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_build_run WHERE batch_id = ? AND state IN "
                        + "('FAILED','CANCELLED','STALE')", Long.class, batchId);
        Long succeeded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_build_run WHERE batch_id = ? AND state IN "
                        + "('READY','PUBLISHED')", Long.class, batchId);
        Long cancelled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_build_run WHERE batch_id = ? AND state = 'CANCELLED'",
                Long.class, batchId);
        boolean anyFailed = failing != null && failing > 0;
        boolean anySucceeded = succeeded != null && succeeded > 0;
        // 全部子任务都是被取消的：批次显示已取消，而不是失败
        boolean allCancelled = anyFailed && !anySucceeded && cancelled != null
                && cancelled.equals(failing);
        String target = !anyFailed ? "READY" : anySucceeded ? "PARTIAL_FAILED"
                : allCancelled ? "CANCELLED" : "FAILED";
        jdbc.update("UPDATE graph_build_batch SET state = ? WHERE id = ?", target, batchId);
    }

    @Override
    @Transactional
    public long persistManifest(GraphSourceManifest manifest) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO graph_source_manifest "
                            + "(run_id, kb_id, manifest_hash, content_epoch, security_epoch, "
                            + "event_watermark, entry_count, state, completed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETE', CURRENT_TIMESTAMP(6))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, manifest.runId());
            statement.setLong(2, manifest.kbId());
            statement.setString(3, manifest.manifestHash());
            statement.setLong(4, manifest.contentEpoch());
            statement.setLong(5, manifest.securityEpoch());
            statement.setLong(6, manifest.eventWatermark());
            statement.setLong(7, manifest.entries().size());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("来源清单插入未返回主键");
        long manifestId = key.longValue();
        for (GraphSourceManifestEntry entry : manifest.entries()) {
            GraphSourceChunk source = entry.sourceChunk();
            jdbc.update("INSERT INTO graph_source_manifest_entry "
                            + "(manifest_id, source_chunk_id, resource_type, resource_id, revision_id, "
                            + "lifecycle_version, chunk_key, content_hash, parser_version, chunker_version, "
                            + "chunk_index_version, entity_linking_version, chunk_state) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    manifestId, source.sourceChunkId(), source.resourceType(), source.resourceId(),
                    source.revisionId(), source.lifecycleVersion(), source.chunkKey(),
                    source.contentHash(), source.parserVersion(), source.chunkerVersion(),
                    source.indexVersion(), entry.entityLinkingVersion(), entry.readiness().name());
        }
        return manifestId;
    }

    @Override
    @Transactional
    public long createSnapshot(GraphBuildRunRecord run, long manifestId, String manifestHash,
                               GraphSnapshotIdentity identity) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO graph_snapshot "
                            + "(run_id, kb_id, graph_version, chunk_index_version, chunk_physical_index, "
                            + "community_index_version, community_physical_index, source_manifest_id, "
                            + "source_manifest_hash, entity_linking_version, chunk_index_schema, "
                            + "graph_schema_version, algorithm_mode, engine_version, summary_model, "
                            + "summary_prompt_version, embedding_model, embedding_dimensions, "
                            + "content_epoch, security_epoch, state, "
                            + "extractor, community_detector, summarizer) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BUILDING', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, run.id());
            statement.setLong(2, run.kbId());
            statement.setLong(3, run.graphVersion());
            statement.setInt(4, run.chunkIndexVersion());
            statement.setString(5, run.chunkPhysicalIndex());
            statement.setLong(6, run.communityIndexVersion());
            statement.setString(7, run.communityPhysicalIndex());
            statement.setLong(8, manifestId);
            statement.setString(9, manifestHash);
            statement.setString(10, run.entityLinkingVersion());
            statement.setString(11, run.chunkIndexSchema());
            statement.setInt(12, identity.graphSchemaVersion());
            statement.setString(13, identity.algorithmMode());
            statement.setString(14, identity.engineVersion());
            statement.setString(15, identity.summaryModel());
            statement.setString(16, identity.summaryPromptVersion());
            statement.setString(17, identity.embeddingModel());
            if (identity.embeddingDimensions() == null) statement.setNull(18, java.sql.Types.INTEGER);
            else statement.setInt(18, identity.embeddingDimensions());
            statement.setLong(19, run.contentEpoch());
            statement.setLong(20, run.securityEpoch());
            statement.setString(21, identity.extractor());
            statement.setString(22, identity.communityDetector());
            statement.setString(23, identity.summarizer());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("图快照插入未返回主键");
        return key.longValue();
    }

    @Override
    public Optional<GraphBuildingSnapshot> findBuildingSnapshot(long runId) {
        return jdbc.query("SELECT id, source_manifest_id, source_manifest_hash FROM graph_snapshot "
                        + "WHERE run_id = ? AND state = 'BUILDING' ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? Optional.of(new GraphBuildingSnapshot(rs.getLong(1),
                        rs.getLong(2), rs.getString(3))) : Optional.empty(), runId);
    }

    @Override
    public boolean isCancelled(long runId) {
        return jdbc.query("SELECT state FROM graph_build_run WHERE id = ?",
                rs -> rs.next() && "CANCELLED".equals(rs.getString(1)), runId);
    }

    @Override
    public boolean updateSnapshotCounts(long snapshotId, long entities, long relations,
                                        long communities) {
        return jdbc.update("UPDATE graph_snapshot SET entity_count = ?, relation_count = ?, "
                        + "community_count = ? WHERE id = ? AND state = 'BUILDING'",
                entities, relations, communities, snapshotId) == 1;
    }

    private static GraphBuildRunRecord mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GraphBuildRunRecord(
                rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("kb_id"),
                rs.getInt("chunk_index_version"), rs.getString("chunk_physical_index"),
                rs.getLong("community_index_version"), rs.getString("community_physical_index"),
                rs.getLong("graph_version"), rs.getString("chunk_index_schema"),
                rs.getString("entity_linking_version"), rs.getLong("content_epoch"),
                rs.getLong("security_epoch"), GraphBuildState.valueOf(rs.getString("state")),
                GraphBuildStage.valueOf(rs.getString("stage")), rs.getLong("fencing_token"),
                rs.getString("lease_owner"), rs.getTimestamp("lease_expires_at") == null ? null
                        : rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getLong("event_watermark"),
                rs.getString("extractor"), rs.getString("community_detector"),
                rs.getString("summarizer"));
    }

    private static String activeStateSql() {
        return "'QUEUED','EXTRACTING','PROJECTING','CLUSTERING','SUMMARIZING','INDEXING',"
                + "'VALIDATING','WAITING_FOR_CHUNKS','UNSUPPORTED','NEEDS_ATTENTION'";
    }
}
