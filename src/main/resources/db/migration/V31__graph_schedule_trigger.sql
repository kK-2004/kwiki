-- 每日调度触发记录：日期主键保证当天最多触发/补跑一次；
-- SKIPPED_ACTIVE 记录因未完成批次而跳过的日期及关联任务。
CREATE TABLE graph_schedule_trigger (
    schedule_date   DATE        NOT NULL,
    status          VARCHAR(20) NOT NULL,
    linked_batch_id BIGINT      NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (schedule_date),
    CONSTRAINT ck_graph_schedule_trigger_status CHECK
        (status IN ('TRIGGERED', 'SKIPPED_ACTIVE', 'CATCH_UP'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_schedule_trigger_batch ON graph_schedule_trigger (linked_batch_id);
