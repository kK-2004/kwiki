package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 受权限约束的用户名候选人与应用内通知状态。 */
@Service
public class MentionNotificationService {

    private static final String CANDIDATE_SQL = "SELECT DISTINCT u.id, u.username, u.display_name "
            + "FROM app_user u JOIN wiki_page p ON p.id = ? "
            + "LEFT JOIN wiki_page_member pm ON pm.page_id = p.id AND pm.user_id = u.id "
            + "LEFT JOIN wiki_page_audience_member pa ON pa.page_id = p.id AND pa.user_id = u.id "
            + "LEFT JOIN knowledge_base_member source_member ON source_member.kb_id = pa.source_kb_id "
            + "AND source_member.user_id = u.id "
            + "LEFT JOIN knowledge_base_member km ON km.kb_id = p.kb_id AND km.user_id = u.id "
            + "WHERE u.is_active = TRUE AND (u.is_admin = TRUE OR p.owner_id = u.id "
            + "OR pm.role IN ('VIEWER','EDITOR','ADMIN') "
            + "OR (p.audience_mode = 'SELECTED_MEMBERS' AND source_member.id IS NOT NULL) "
            + "OR (p.audience_mode = 'KB_MEMBERS' AND km.id IS NOT NULL)) "
            + "AND LOWER(u.username) LIKE ? ESCAPE '\\\\' "
            + "ORDER BY CASE WHEN LOWER(u.username) = LOWER(?) THEN 0 "
            + "WHEN LOWER(u.username) LIKE LOWER(?) ESCAPE '\\\\' THEN 1 "
            + "WHEN LOWER(u.username) LIKE ? ESCAPE '\\\\' THEN 2 ELSE 3 END, u.username, u.id LIMIT ?";

    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;

    public MentionNotificationService(ObjectProvider<JdbcOperations> jdbc,
                                      ResourceAuthorizationService authorization) {
        this.jdbc = jdbc.getIfAvailable();
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public List<UserCandidate> candidates(CurrentUser user, long pageId, String query, int limit) {
        requireRead(user, pageId);
        String normalized = query == null ? "" : query.trim().toLowerCase();
        String escaped = escapeLike(normalized);
        int bounded = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        return jdbc.query(CANDIDATE_SQL, (rs, row) -> new UserCandidate(
                        rs.getLong("id"), rs.getString("username"), rs.getString("display_name")),
                pageId,
                escaped.isEmpty() ? "%" : escaped + "%",
                normalized,
                escaped.isEmpty() ? "%" : escaped + "%",
                "%" + (escaped.isEmpty() ? "" : escaped) + "%",
                bounded);
    }

    @Transactional
    public void attachMentions(CurrentUser author, long pageId, long commentId,
                               List<Long> recipientIds, Long anchorId) {
        requireRead(author, pageId);
        if (recipientIds == null) return;
        for (Long recipientId : recipientIds.stream().distinct().toList()) {
            if (recipientId == null || recipientId.equals(author.id())) continue;
            String username = jdbc.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, recipientId);
            boolean allowed = username != null && candidates(author, pageId, username, 1).stream()
                    .anyMatch(candidate -> candidate.id() == recipientId);
            if (!allowed) throw new IllegalArgumentException("mention recipient is not accessible");
            jdbc.update("INSERT IGNORE INTO comment_mention (comment_id, recipient_id) VALUES (?, ?)", commentId, recipientId);
            jdbc.update("INSERT IGNORE INTO notification (recipient_id, type, page_id, comment_id, anchor_id) "
                    + "VALUES (?, 'MENTION', ?, ?, ?)", recipientId, pageId, commentId, anchorId);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationView> notifications(CurrentUser user, int limit) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        int bounded = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        return jdbc.query("SELECT n.id, n.type, n.page_id, p.kb_id, n.comment_id, n.anchor_id, n.read_at, n.created_at, p.title "
                        + "FROM notification n LEFT JOIN wiki_page p ON p.id = n.page_id "
                        + "LEFT JOIN wiki_comment c ON c.id = n.comment_id "
                        + "LEFT JOIN wiki_comment root ON root.id = c.parent_id "
                        + "WHERE n.recipient_id = ? AND (n.comment_id IS NULL OR (c.deleted_at IS NULL "
                        + "AND (c.parent_id IS NULL OR root.deleted_at IS NULL))) "
                        + "ORDER BY n.created_at DESC, n.id DESC LIMIT ?",
                (rs, row) -> new NotificationView(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class),
                        rs.getObject(4, Long.class), rs.getObject(5, Long.class), rs.getObject(6, Long.class),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8).toInstant(), rs.getString(9)), user.id(), bounded).stream()
                .filter(item -> item.pageId() == null || authorization.canRead(user, item.pageId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(CurrentUser user) {
        return notifications(user, 1000).stream().filter(item -> item.readAt() == null).count();
    }

    @Transactional
    public void markRead(CurrentUser user, long notificationId) {
        jdbc.update("UPDATE notification SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(6)) "
                + "WHERE id = ? AND recipient_id = ?", notificationId, user.id());
    }

    @Transactional
    public void markAllRead(CurrentUser user) {
        jdbc.update("UPDATE notification SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(6)) "
                + "WHERE recipient_id = ? AND read_at IS NULL", user.id());
    }

    private void requireRead(CurrentUser user, long pageId) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        authorization.require(user, pageId, com.kwiki.wiki.access.ResourceAction.READ);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record UserCandidate(long id, String username, String displayName) {}
    public record NotificationView(long id, String type, Long pageId, Long kbId, Long commentId, Long anchorId,
                                   Instant readAt, Instant createdAt, String pageTitle) {}
}
