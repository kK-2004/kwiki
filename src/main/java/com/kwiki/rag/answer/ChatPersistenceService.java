package com.kwiki.rag.answer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Persists chat turns and non-sensitive agent trace metadata after completion or failure. Raw
 * provider credentials and unrestricted evidence are never written: traces store only counts,
 * modes, sources, and durations.
 */
@Service
public class ChatPersistenceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcOperations jdbc;
    private io.micrometer.core.instrument.MeterRegistry metrics;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(io.micrometer.core.instrument.MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public ChatPersistenceService(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public void persistTurn(
            String sessionUuid,
            long userId,
            String userQuery,
            String assistantAnswer,
            Map<String, Object> traceMetadata) {
        persistTurn(
                sessionUuid,
                userId,
                userQuery,
                assistantAnswer,
                traceMetadata,
                MDC.get("traceId") == null ? sessionUuid : MDC.get("traceId"));
    }

    public void persistTurn(
            String sessionUuid,
            long userId,
            String userQuery,
            String assistantAnswer,
            Map<String, Object> traceMetadata,
            String traceId) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT IGNORE INTO chat_session (uuid, user_id, title) VALUES (?, ?, ?)",
                    sessionUuid,
                    userId,
                    truncate(userQuery, 200));
            Long sessionId =
                    jdbc.queryForObject(
                            "SELECT id FROM chat_session WHERE uuid = ?", Long.class, sessionUuid);
            if (sessionId == null) {
                return;
            }
            jdbc.update(
                    "INSERT INTO chat_message (session_id, role, content) VALUES (?, 'USER', ?)",
                    sessionId,
                    userQuery);
            if (assistantAnswer != null && !assistantAnswer.isBlank()) {
                jdbc.update(
                        "INSERT INTO chat_message (session_id, role, content) VALUES (?,"
                            + " 'ASSISTANT', ?)",
                        sessionId,
                        assistantAnswer);
            }
            jdbc.update(
                    "INSERT INTO request_trace (correlation_id, user_id, kind, trace_json) "
                            + "VALUES (?, ?, 'CHAT', ?)",
                    traceId,
                    userId,
                    MAPPER.writeValueAsString(traceMetadata));
        } catch (Exception e) {
            if (metrics != null) metrics.counter("kwiki_chat_audit_failures_total").increment();
            // persistence failures must never fail the chat stream
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
