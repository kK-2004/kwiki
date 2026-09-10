package com.kwiki.rag.answer;

import com.kwiki.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.kwiki.rag.rewrite.ChatTurn;

/** 负责持久化的会话/运行状态；工作流本身与存储无关。 */
@Service
public class ChatSessionService {
    private final JdbcOperations jdbc;

    public ChatSessionService(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverInterruptedRuns() {
        if (jdbc != null) {
            jdbc.update("UPDATE chat_run SET status = 'INTERRUPTED', finished_at = CURRENT_TIMESTAMP(6), error_code = 'process_restarted' WHERE status = 'RUNNING'");
        }
    }

    @Transactional(readOnly = true)
    public List<SessionView> list(CurrentUser user) {
        return listPage(user, 50, null).items();
    }

    @Transactional(readOnly = true)
    public SessionPage listPage(CurrentUser user, int limit, String cursor) {
        requireDb();
        int bounded = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
        Instant after = decodeCursor(cursor);
        String sql = "SELECT uuid, title, created_at, updated_at FROM chat_session WHERE user_id = ? AND deleted_at IS NULL "
                + (after == null ? "" : "AND updated_at < ? ")
                + "ORDER BY updated_at DESC, id DESC LIMIT " + (bounded + 1);
        List<SessionView> rows = after == null
                ? jdbc.query(sql, (rs, row) -> new SessionView(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()), user.id())
                : jdbc.query(sql, (rs, row) -> new SessionView(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()), user.id(), java.sql.Timestamp.from(after));
        String next = rows.size() > bounded ? encodeCursor(rows.get(bounded - 1)) : null;
        if (rows.size() > bounded) rows = rows.subList(0, bounded);
        return new SessionPage(rows, next);
    }

    @Transactional(readOnly = true)
    public SessionDetail detail(CurrentUser user, String sessionUuid) {
        requireDb();
        Long id = sessionId(user, sessionUuid);
        var messages = jdbc.query("SELECT id, role, content, created_at, run_id FROM (SELECT m.id, m.role, m.content, m.created_at, m.run_id FROM chat_message m "
                        + "WHERE m.session_id = ? ORDER BY m.created_at DESC, m.id DESC LIMIT 200) recent ORDER BY created_at, id",
                (rs, row) -> new MessageView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), runRequestId(rs.getLong(5))), id);
        String title = jdbc.queryForObject("SELECT title FROM chat_session WHERE id = ?", String.class, id);
        return new SessionDetail(sessionUuid, title, messages);
    }

    private String runRequestId(long runId) {
        if (runId == 0) return null;
        try {
            return jdbc.queryForObject("SELECT request_id FROM chat_run WHERE id = ?", String.class, runId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @Transactional
    public void rename(CurrentUser user, String sessionUuid, String title) {
        requireDb();
        Long id = sessionId(user, sessionUuid);
        jdbc.update("UPDATE chat_session SET title = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND deleted_at IS NULL",
                truncate(title), id);
    }

    @Transactional
    public void delete(CurrentUser user, String sessionUuid) {
        requireDb();
        Long id = sessionId(user, sessionUuid);
        jdbc.update("UPDATE chat_session SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND deleted_at IS NULL", id);
    }

    @Transactional
    public void cancel(CurrentUser user, String sessionUuid, String requestId) {
        cancel(user, sessionUuid, requestId, null);
    }

    @Transactional
    public void cancel(CurrentUser user, String sessionUuid, String requestId, String partialAnswer) {
        requireDb();
        Long sessionId = sessionId(user, sessionUuid);
        Long runId = jdbc.query("SELECT id FROM chat_run WHERE session_id = ? AND request_id = ? AND status = 'RUNNING'",
                rs -> rs.next() ? rs.getLong(1) : null, sessionId, requestId);
        if (runId == null) return;
        int changed = jdbc.update("UPDATE chat_run SET status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP(6), error_code = 'cancelled' "
                + "WHERE session_id = ? AND request_id = ? AND status = 'RUNNING'", sessionId, requestId);
        if (changed == 0) return;
        if (partialAnswer != null && !partialAnswer.isBlank()) {
            jdbc.update("INSERT INTO chat_message (session_id, run_id, role, content) VALUES (?, ?, 'ASSISTANT', ?)",
                    sessionId, runId, partialAnswer.substring(0, Math.min(partialAnswer.length(), 100000)));
        }
    }

    @Transactional
    public RunHandle begin(CurrentUser user, String requestedSessionUuid, String clientMessageId, String agentId, String query) {
        requireDb();
        String sessionUuid = requestedSessionUuid == null || requestedSessionUuid.isBlank()
                ? UUID.randomUUID().toString() : requestedSessionUuid;
        Long sessionId;
        try {
            sessionId = jdbc.queryForObject("SELECT id FROM chat_session WHERE uuid = ? AND user_id = ? AND deleted_at IS NULL",
                    Long.class, sessionUuid, user.id());
        } catch (EmptyResultDataAccessException ex) {
            var foreign = jdbc.query("SELECT user_id, deleted_at FROM chat_session WHERE uuid = ?",
                    (rs, row) -> new Object[] { rs.getLong(1), rs.getTimestamp(2) }, sessionUuid);
            if (!foreign.isEmpty()) throw new AccessDeniedException("session is unavailable");
            jdbc.update("INSERT INTO chat_session (uuid, user_id, title, agent_id) VALUES (?, ?, ?, ?)",
                    sessionUuid, user.id(), truncate(query), agentId);
            sessionId = jdbc.queryForObject("SELECT id FROM chat_session WHERE uuid = ? AND user_id = ?", Long.class, sessionUuid, user.id());
        }
        String clientId = clientMessageId == null || clientMessageId.isBlank() ? UUID.randomUUID().toString() : clientMessageId;
        var existing = jdbc.query("SELECT id, request_id, status FROM chat_run WHERE session_id = ? AND client_message_id = ?",
                (rs, row) -> new ExistingRun(rs.getLong(1), rs.getString(2), rs.getString(3)), sessionId, clientId);
        if (!existing.isEmpty()) return new RunHandle(sessionUuid, clientId, existing.get(0).requestId(), sessionId, existing.get(0).id(), true);
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_run WHERE session_id = ? AND status = 'RUNNING'",
                Integer.class, sessionId);
        if (running != null && running > 0) throw new RunConflictException("session already has a running turn");
        String requestId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_run (uuid, session_id, user_id, client_message_id, request_id, status) VALUES (?, ?, ?, ?, ?, 'RUNNING')",
                UUID.randomUUID().toString(), sessionId, user.id(), clientId, requestId);
        Long runId = jdbc.queryForObject("SELECT id FROM chat_run WHERE request_id = ?", Long.class, requestId);
        jdbc.update("INSERT INTO chat_message (session_id, run_id, role, content) VALUES (?, ?, 'USER', ?)", sessionId, runId, query);
        return new RunHandle(sessionUuid, clientId, requestId, sessionId, runId, false);
    }

    @Transactional(readOnly = true)
    public List<ChatTurn> historyBefore(long runId) {
        if (jdbc == null) return List.of();
        // 助手消息可能包含引用，而这些引用对应的页面访问权限在该轮
        // 之后发生了变化。只有此前的用户提问可以安全复用，无需再通过
        // 文档授权服务逐条重新加载每个历史引用。
        List<ChatTurn> rows = jdbc.query("SELECT m.role, m.content FROM chat_message m JOIN chat_run r ON r.session_id = m.session_id "
                        + "WHERE r.id = ? AND m.id < (SELECT MIN(id) FROM chat_message WHERE run_id = ?) AND m.role = 'USER' "
                        + "ORDER BY m.id DESC LIMIT 12", (rs, row) -> new ChatTurn(rs.getString(1), truncateContext(rs.getString(2))), runId, runId);
        java.util.Collections.reverse(rows);
        return rows;
    }

    @Transactional
    public void finish(CurrentUser user, RunHandle run, String query, String answer, boolean success, String error) {
        if (jdbc == null || run == null || run.existing()) return;
        Long runId = jdbc.queryForObject("SELECT id FROM chat_run WHERE request_id = ? AND user_id = ?", Long.class, run.requestId(), user.id());
        if (runId == null) return;
        int changed = jdbc.update("UPDATE chat_run SET status = ?, finished_at = CURRENT_TIMESTAMP(6), error_code = ?, final_seq = COALESCE(final_seq, 0) WHERE id = ? AND status = 'RUNNING'",
                success ? "SUCCEEDED" : "FAILED", error, runId);
        if (changed == 0) return;
        if (answer != null && !answer.isBlank()) {
            jdbc.update("INSERT INTO chat_message (session_id, run_id, role, content) VALUES (?, ?, 'ASSISTANT', ?)", run.sessionId(), runId, answer);
        }
        jdbc.update("UPDATE chat_session SET updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", run.sessionId());
    }

    private Long sessionId(CurrentUser user, String uuid) {
        if (uuid == null || uuid.isBlank()) throw new IllegalArgumentException("session id is required");
        try {
            return jdbc.queryForObject("SELECT id FROM chat_session WHERE uuid = ? AND user_id = ? AND deleted_at IS NULL", Long.class, uuid, user.id());
        } catch (EmptyResultDataAccessException ex) {
            throw new AccessDeniedException("session is unavailable");
        }
    }

    private void requireDb() { if (jdbc == null) throw new IllegalStateException("database is unavailable"); }
    private String truncate(String value) { if (value == null || value.isBlank()) return "新会话"; return value.trim().substring(0, Math.min(value.trim().length(), 300)); }
    private String truncateContext(String value) { if (value == null) return ""; return value.length() <= 2000 ? value : value.substring(0, 2000); }
    private static String encodeCursor(SessionView item) { return Long.toString(item.updatedAt().toEpochMilli()); }
    private static Instant decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.ofEpochMilli(Long.parseLong(value)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("invalid session cursor"); }
    }

    public record SessionView(String id, String title, Instant createdAt, Instant updatedAt) {}
    public record SessionPage(List<SessionView> items, String nextCursor) {}
    /** requestId 将助手消息与它已存储的运行事件关联起来（活动回放）。 */
    public record MessageView(long id, String role, String content, Instant createdAt, String requestId) {}
    public record SessionDetail(String id, String title, List<MessageView> messages) {}
    public record RunHandle(String sessionUuid, String clientMessageId, String requestId, long sessionId, long runId, boolean existing) {}
    public static final class RunConflictException extends RuntimeException {
        public RunConflictException(String message) { super(message); }
    }
    private record ExistingRun(long id, String requestId, String status) {}
}
