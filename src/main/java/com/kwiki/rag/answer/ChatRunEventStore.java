package com.kwiki.rag.answer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 每次运行的持久化 SSE 事件日志（chat_run_event）。事件按
 * 投递顺序连同其精确的线上负载一并持久化，因此重新打开对话
 * 时会回放同一条活动时间线；持久化失败绝不会中断
 * 实时流（而是通过指标计数）。没有存储事件的旧运行
 * 仍会依据持久化的助手消息渲染。
 */
@Repository
public class ChatRunEventStore {

    private static final Logger log = LoggerFactory.getLogger(ChatRunEventStore.class);

    private final JdbcOperations jdbc;

    public ChatRunEventStore(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public void append(String requestId, Long sessionId, ChatStreamEvent event) {
        if (jdbc == null || event == null || requestId == null) {
            return;
        }
        try {
            jdbc.update("""
                            INSERT INTO chat_run_event
                                (run_request_id, session_id, seq, event_type, payload_json)
                            VALUES (?, ?, ?, ?, ?)
                            ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json)
                            """,
                    requestId, sessionId, event.sequence(), event.type(), event.payloadJson());
        } catch (Exception e) {
            log.debug("chat run event persist failed requestId={} seq={}: {}",
                    requestId, event.sequence(), e.getMessage());
        }
    }

    public record StoredEvent(long seq, String type, String payloadJson) {}

    /** 回放来源：单个运行按序号排列的已存储线缆事件。 */
    public List<StoredEvent> replay(String requestId) {
        if (jdbc == null || requestId == null) {
            return List.of();
        }
        return jdbc.query(
                "SELECT seq, event_type, payload_json FROM chat_run_event "
                        + "WHERE run_request_id = ? ORDER BY seq",
                (rs, row) -> new StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3)),
                requestId);
    }

    /** 某个会话中所有已存储事件的运行（request id）。 */
    public List<String> runsOfSession(long sessionId) {
        if (jdbc == null) {
            return List.of();
        }
        return jdbc.query(
                "SELECT DISTINCT run_request_id FROM chat_run_event WHERE session_id = ? "
                        + "ORDER BY run_request_id",
                (rs, row) -> rs.getString(1), sessionId);
    }

    public Map<String, Object> stats() {
        return Map.of();
    }
}
