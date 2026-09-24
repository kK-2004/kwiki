package com.kwiki.graph.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 内容和安全变化的单调 epoch，作为旧图任务和摘要的生命周期 fencing。 */
@Service
public class GraphSourceEpochService {

    private final JdbcTemplate jdbc;

    public GraphSourceEpochService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long advanceContentEpoch(long kbId) {
        ensure(kbId);
        jdbc.update("UPDATE graph_source_epoch SET content_epoch = content_epoch + 1 "
                + "WHERE kb_id = ?", kbId);
        return current(kbId)[0];
    }

    @Transactional
    public long advanceSecurityEpoch(long kbId) {
        ensure(kbId);
        jdbc.update("UPDATE graph_source_epoch SET security_epoch = security_epoch + 1 "
                + "WHERE kb_id = ?", kbId);
        return current(kbId)[1];
    }

    public long[] current(long kbId) {
        return jdbc.query("SELECT content_epoch, security_epoch FROM graph_source_epoch WHERE kb_id = ?",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, 0}, kbId);
    }

    /** 发布事务使用的锁定读取，避免 epoch 在 CAS 前被并发内容变更穿透。 */
    @Transactional
    public long[] currentForUpdate(long kbId) {
        ensure(kbId);
        return jdbc.query("SELECT content_epoch, security_epoch FROM graph_source_epoch "
                        + "WHERE kb_id = ? FOR UPDATE",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, 0}, kbId);
    }

    private void ensure(long kbId) {
        jdbc.update("INSERT IGNORE INTO graph_source_epoch (kb_id) VALUES (?)", kbId);
    }
}
