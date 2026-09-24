package com.kwiki.indexing.version;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** 基线重建专用的单目标入队器；不会读取或改变实时 writeEnabled 集合。 */
@Repository
class RebuildTargetEnqueuer {
    private final JdbcOperations jdbc;

    RebuildTargetEnqueuer(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    void enqueuePage(long runId, int version, String physicalName, long pageId,
                     long revisionId, long lifecycleVersion) {
        enqueue(runId, version, physicalName, "PAGE", pageId, revisionId,
                lifecycleVersion);
    }

    void enqueueAttachment(long runId, int version, String physicalName, long attachmentId) {
        enqueue(runId, version, physicalName, "ATTACHMENT", attachmentId, null, null);
    }

    void enqueueTailPage(long runId, int version, String physicalName, long pageId,
                         long revisionId, long lifecycleVersion) {
        enqueue("CATCHUP:" + runId + ":TAIL", null, version, physicalName,
                "UPSERT", "PAGE", pageId, revisionId, lifecycleVersion);
    }

    void enqueueTailAttachment(long runId, int version, String physicalName, long attachmentId) {
        enqueue("CATCHUP:" + runId + ":TAIL", null, version, physicalName,
                "UPSERT", "ATTACHMENT", attachmentId, null, null);
    }

    void replayEvent(long runId, int version, String physicalName, ChangeEvent event) {
        String operation = switch (event.operation()) {
            case "ARCHIVE" -> "DELETE";
            case "RESTORE" -> "UPSERT";
            case "UPSERT", "DELETE" -> event.operation();
            default -> throw new IllegalArgumentException(
                    "unsupported change-event operation: " + event.operation());
        };
        enqueue("CATCHUP:" + runId + ":EVENT:" + event.id(), event.id(), version,
                physicalName, operation, event.resourceType(), event.resourceId(),
                event.revisionId(), event.lifecycleVersion());
    }

    private void enqueue(long runId, int version, String physicalName, String resourceType,
                         long resourceId, Long revisionId, Long lifecycleVersion) {
        enqueue("REBUILD:" + runId, null, version, physicalName, "UPSERT", resourceType,
                resourceId, revisionId, lifecycleVersion);
    }

    private void enqueue(String keyPrefix, Long eventId, int version, String physicalName,
                         String operation, String resourceType, long resourceId,
                         Long revisionId, Long lifecycleVersion) {
        String key = keyPrefix + ":" + resourceType + ":" + resourceId + ":"
                + (revisionId == null ? "-" : revisionId) + ":" + operation;
        jdbc.update("""
                INSERT INTO indexing_job
                    (job_type, resource_type, resource_id, revision_id,
                     expected_lifecycle_version, idempotency_key, state, attempts, max_attempts)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, 8)
                ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP(6)
                """, operation, resourceType, resourceId, revisionId, lifecycleVersion, key);
        Long jobId = jdbc.queryForObject(
                "SELECT id FROM indexing_job WHERE idempotency_key=?", Long.class, key);
        if (jobId == null) throw new IllegalStateException("rebuild job insert returned no id");
        jdbc.update("""
                INSERT INTO indexing_job_target
                    (job_id, event_id, target_version, physical_name,
                     idempotency_key, state, attempts, max_attempts)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, 8)
                ON DUPLICATE KEY UPDATE physical_name=VALUES(physical_name)
                """, jobId, eventId, version, physicalName, key + ":v" + version);
    }

    record ChangeEvent(long id, String resourceType, long resourceId, Long revisionId,
                       String operation, long lifecycleVersion) { }
}
