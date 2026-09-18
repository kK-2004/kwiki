package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 基于 MySQL 的入队器：在发布/归档事务内被调用，当且仅当内容变更
 * 提交时才会留下任务记录。每次入队在同一个事务里完成三件事：
 *
 * <ol>
 *   <li>追加一条单调 {@code search_index_change_event}（内容/生命周期
 *       事件 outbox，供切换准备期间按事件 ID 重放）；</li>
 *   <li>幂等 upsert {@code indexing_job}（键
 *       resourceType:resourceId:revisionId:operation）；</li>
 *   <li>扇出 {@code indexing_job_target}：快照当下全部 writeEnabled 且
 *       未删除的物理版本，各自独立 PENDING，幂等键含事件与目标版本。
 *       别名此后无论怎么切换，排队中的任务都不会被重定向。</li>
 * </ol>
 *
 * <p>可选的预期生命周期版本用于防护工作线程，防止在归档/恢复
 * 切换使资源版本递增之后执行过期的工作。</p>
 */
@Repository
@Transactional
public class JdbcIndexingJobEnqueuer implements IndexingJobEnqueuer {

    private final JdbcOperations jdbc;

    public JdbcIndexingJobEnqueuer(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @Override
    public void enqueuePageUpsert(long pageId, long revisionId) {
        enqueue("UPSERT", "PAGE", pageId, revisionId, null, null);
    }

    @Override
    public void enqueuePageDelete(long pageId) {
        enqueue("DELETE", "PAGE", pageId, null, null, null);
    }

    @Override
    public void enqueueAttachmentUpsert(long attachmentId) {
        enqueue("UPSERT", "ATTACHMENT", attachmentId, null, null, null);
    }

    @Override
    public void enqueueAttachmentDelete(long attachmentId) {
        enqueue("DELETE", "ATTACHMENT", attachmentId, null, null, null);
    }

    @Override
    public void enqueuePageUpsert(long pageId, long revisionId, long expectedLifecycleVersion) {
        enqueue("UPSERT", "PAGE", pageId, revisionId, null, expectedLifecycleVersion);
    }

    @Override
    public void enqueuePageDelete(long pageId, long expectedLifecycleVersion) {
        enqueue("DELETE", "PAGE", pageId, null, null, expectedLifecycleVersion);
    }

    @Override
    public void enqueueAttachmentDelete(long attachmentId, long expectedLifecycleVersion) {
        enqueue("DELETE", "ATTACHMENT", attachmentId, null, null, expectedLifecycleVersion);
    }

    @Override
    public void enqueueKnowledgeBaseDelete(long kbId, long expectedLifecycleVersion) {
        enqueue("DELETE", "KNOWLEDGE_BASE", kbId, null, kbId, expectedLifecycleVersion);
    }

    private void enqueue(String jobType, String resourceType, long resourceId,
                         Long revisionId, Long kbId, Long expectedLifecycleVersion) {
        if (jdbc == null) {
            return;
        }
        lockVersionSnapshot();
        long eventId = appendChangeEvent(jobType, resourceType, resourceId, revisionId,
                kbId, expectedLifecycleVersion);
        long jobId = upsertJob(jobType, resourceType, resourceId, revisionId,
                expectedLifecycleVersion);
        fanOutTargets(jobId, eventId);
    }

    /**
     * 与切换准备对版本行的排他锁组成提交边界：边界前的事件必然进入
     * dualWriteStartEventId，边界后的事件必然看到新的 writeEnabled 集合。
     * 调用方事务会把共享锁持有到内容事件与目标快照一同提交。
     */
    private void lockVersionSnapshot() {
        jdbc.queryForList("SELECT version_number FROM search_index_version "
                + "WHERE deleted_at IS NULL ORDER BY version_number FOR SHARE", Integer.class);
    }

    /** 追加式事件表只插入，永不更新或删除。 */
    private long appendChangeEvent(String jobType, String resourceType, long resourceId,
                                    Long revisionId, Long kbId, Long expectedLifecycleVersion) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO search_index_change_event"
                            + " (resource_type, resource_id, revision_id, kb_id, operation,"
                            + " lifecycle_version, origin)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'LIVE')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, resourceType);
            statement.setLong(2, resourceId);
            statement.setObject(3, revisionId);
            statement.setObject(4, kbId);
            statement.setString(5, jobType);
            statement.setLong(6, expectedLifecycleVersion == null ? 0L : expectedLifecycleVersion);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("change event insert returned no key");
        }
        return key.longValue();
    }

    private long upsertJob(String jobType, String resourceType, long resourceId,
                           Long revisionId, Long expectedLifecycleVersion) {
        String idempotencyKey = resourceType + ":" + resourceId + ":"
                + (revisionId == null ? "-" : revisionId) + ":" + jobType;
        jdbc.update("""
                        INSERT INTO indexing_job
                            (job_type, resource_type, resource_id, revision_id,
                             expected_lifecycle_version, idempotency_key,
                             state, attempts, max_attempts)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, 8)
                        ON DUPLICATE KEY UPDATE
                            state = IF(state IN ('COMPLETED', 'FAILED'), 'PENDING', state),
                            expected_lifecycle_version = VALUES(expected_lifecycle_version),
                            updated_at = CURRENT_TIMESTAMP(6)
                        """,
                jobType, resourceType, resourceId, revisionId, expectedLifecycleVersion,
                idempotencyKey);
        Long jobId = jdbc.queryForObject(
                "SELECT id FROM indexing_job WHERE idempotency_key = ?", Long.class, idempotencyKey);
        if (jobId == null) {
            throw new IllegalStateException("indexing job upsert lost the row: " + idempotencyKey);
        }
        return jobId;
    }

    /**
     * 入队时刻快照全部 writeEnabled 目标。之后的写目标集合变化
     * （切换准备开启全版本双写、管理员停用）不会增删本事件的既有
     * 目标行——新目标只承接其启用之后的事件。
     */
    private void fanOutTargets(long jobId, long eventId) {
        jdbc.update("""
                        INSERT INTO indexing_job_target
                            (job_id, event_id, target_version, physical_name,
                             idempotency_key, state, attempts, max_attempts)
                        SELECT ?, ?, v.version_number, v.physical_name,
                               CONCAT(?, ':v', v.version_number), 'PENDING', 0, 8
                        FROM search_index_version v
                        WHERE v.write_enabled = TRUE AND v.deleted_at IS NULL
                        ON DUPLICATE KEY UPDATE
                            state = IF(indexing_job_target.state IN ('COMPLETED', 'FAILED'),
                                       'PENDING', indexing_job_target.state),
                            event_id = VALUES(event_id),
                            physical_name = VALUES(physical_name),
                            attempts = IF(indexing_job_target.state IN ('COMPLETED', 'FAILED'),
                                          0, attempts),
                            next_attempt_at = NULL,
                            lease_owner = NULL,
                            lease_expires_at = NULL
                        """,
                jobId, eventId, jobId);
    }
}
