package com.kwiki.rag.answer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Durable per-run SSE event log (chat_run_event). Events are persisted in
 * delivery order with their exact wire payloads so reopening a conversation
 * replays the same activity timeline; persistence failures never break the
 * live stream (counted by a metric instead). Old runs without stored events
 * keep rendering from the persisted assistant message.
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

    /** Replay source: stored wire events of one run in seq order. */
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

    /** All runs (request ids) of a session that have stored events. */
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
