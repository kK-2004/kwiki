-- 请求级图快照读取租约：pin 时原子获取，请求结束释放；
-- 退役清理先置 DELETING 使新 pin 失败，再等待既有租约结束。
CREATE TABLE graph_snapshot_read_lease (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    snapshot_id   BIGINT      NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_graph_read_lease_snapshot FOREIGN KEY (snapshot_id) REFERENCES graph_snapshot (id),
    CONSTRAINT ck_graph_read_lease_valid CHECK (expires_at > created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_read_lease_snapshot ON graph_snapshot_read_lease (snapshot_id, expires_at);
