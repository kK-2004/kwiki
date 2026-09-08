package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.infrastructure.redis.WikiStatisticsCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

/** MySQL source of truth for page interactions and two-level comment threads. */
@Service
public class WikiInteractionService {

    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;
    private final MentionNotificationService mentions;
    private final WikiStatisticsCache statisticsCache;

    public WikiInteractionService(ObjectProvider<JdbcOperations> jdbc,
                                  ResourceAuthorizationService authorization,
                                  ObjectProvider<MentionNotificationService> mentions,
                                  WikiStatisticsCache statisticsCache) {
        this.jdbc = jdbc.getIfAvailable();
        this.authorization = authorization;
        this.mentions = mentions.getIfAvailable();
        this.statisticsCache = statisticsCache;
    }

    @Transactional
    public InteractionState setPageLike(CurrentUser user, long pageId, boolean liked) {
        requireRead(user, pageId);
        int changed = liked
                ? jdbc.update("INSERT IGNORE INTO page_like (page_id, user_id) VALUES (?, ?)", pageId, user.id())
                : jdbc.update("DELETE FROM page_like WHERE page_id = ? AND user_id = ?", pageId, user.id());
        long version = bumpStatsVersion(pageId);
        statisticsCache.incrementAfterCommit(pageId, liked ? changed : -changed, 0, 0, version);
        return interactionState(user, pageId);
    }

    @Transactional
    public InteractionState setFavorite(CurrentUser user, long pageId, boolean favorite) {
        requireRead(user, pageId);
        int changed = favorite
                ? jdbc.update("INSERT IGNORE INTO page_favorite (page_id, user_id) VALUES (?, ?)", pageId, user.id())
                : jdbc.update("DELETE FROM page_favorite WHERE page_id = ? AND user_id = ?", pageId, user.id());
        long version = bumpStatsVersion(pageId);
        statisticsCache.incrementAfterCommit(pageId, 0, favorite ? changed : -changed, 0, version);
        return interactionState(user, pageId);
    }

    @Transactional(readOnly = true)
    public InteractionState interactionState(CurrentUser user, long pageId) {
        requireRead(user, pageId);
        var counters = statisticsCache.getOrLoad(pageId, () -> new WikiStatisticsCache.Counters(
                count("SELECT COUNT(*) FROM page_like WHERE page_id = ?", pageId),
                count("SELECT COUNT(*) FROM page_favorite WHERE page_id = ?", pageId),
                count("SELECT COUNT(*) FROM wiki_comment c "
                + "LEFT JOIN wiki_comment root ON root.id = c.parent_id "
                + "WHERE c.page_id = ? AND c.deleted_at IS NULL "
                + "AND (c.parent_id IS NULL OR root.deleted_at IS NULL)", pageId),
                currentStatsVersion(pageId)));
        boolean liked = exists("SELECT 1 FROM page_like WHERE page_id = ? AND user_id = ?", pageId, user.id());
        boolean favorite = exists("SELECT 1 FROM page_favorite WHERE page_id = ? AND user_id = ?", pageId, user.id());
        return new InteractionState(liked, favorite, new PageStatistics(counters.likes(), counters.favorites(), counters.comments()));
    }

    @Transactional(readOnly = true)
    public List<CommentView> comments(CurrentUser user, long pageId) {
        requireRead(user, pageId);
        return jdbc.query("SELECT c.id, c.author_id, u.username, c.body, c.parent_id, c.reply_to, "
                        + "c.anchor_id, c.created_at, c.deleted_at, "
                        + "(SELECT COUNT(*) FROM comment_like cl WHERE cl.comment_id = c.id) AS likes, "
                        + "EXISTS(SELECT 1 FROM comment_like mine WHERE mine.comment_id = c.id AND mine.user_id = ?) AS liked "
                        + "FROM wiki_comment c JOIN app_user u ON u.id = c.author_id "
                        + "LEFT JOIN wiki_comment root ON root.id = c.parent_id "
                        + "WHERE c.page_id = ? AND (c.deleted_at IS NULL OR c.parent_id IS NOT NULL) "
                        + "AND (c.parent_id IS NULL OR root.deleted_at IS NULL) "
                        + "ORDER BY COALESCE(c.parent_id, c.id), c.created_at, c.id",
                (rs, row) -> comment(rs), user.id(), pageId);
    }

    @Transactional(readOnly = true)
    public CommentPage rootComments(CurrentUser user, long pageId, long afterId, int limit) {
        requireRead(user, pageId);
        int bounded = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
        List<CommentView> items = jdbc.query("SELECT c.id, c.author_id, u.username, c.body, c.parent_id, c.reply_to, c.anchor_id, c.created_at, c.deleted_at, "
                        + "(SELECT COUNT(*) FROM comment_like cl WHERE cl.comment_id = c.id), EXISTS(SELECT 1 FROM comment_like mine WHERE mine.comment_id = c.id AND mine.user_id = ?) "
                        + "FROM wiki_comment c JOIN app_user u ON u.id = c.author_id WHERE c.page_id = ? AND c.id > ? AND c.parent_id IS NULL AND c.deleted_at IS NULL ORDER BY c.id LIMIT " + bounded,
                (rs, row) -> comment(rs), user.id(), pageId, afterId);
        return new CommentPage(items, items.size() == bounded ? items.get(items.size() - 1).id() : null);
    }

    @Transactional(readOnly = true)
    public CommentPage replies(CurrentUser user, long pageId, long rootId, long afterId, int limit) {
        requireRead(user, pageId);
        if (!exists("SELECT 1 FROM wiki_comment WHERE id = ? AND page_id = ? AND parent_id IS NULL AND deleted_at IS NULL", rootId, pageId)) {
            throw new IllegalArgumentException("comment root is unavailable");
        }
        int bounded = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
        List<CommentView> items = jdbc.query("SELECT c.id, c.author_id, u.username, c.body, c.parent_id, c.reply_to, c.anchor_id, c.created_at, c.deleted_at, "
                        + "(SELECT COUNT(*) FROM comment_like cl WHERE cl.comment_id = c.id), EXISTS(SELECT 1 FROM comment_like mine WHERE mine.comment_id = c.id AND mine.user_id = ?) "
                        + "FROM wiki_comment c JOIN app_user u ON u.id = c.author_id JOIN wiki_comment root ON root.id = c.parent_id "
                        + "WHERE c.page_id = ? AND c.parent_id = ? AND c.id > ? AND root.deleted_at IS NULL ORDER BY c.id LIMIT " + bounded,
                (rs, row) -> comment(rs), user.id(), pageId, rootId, afterId);
        return new CommentPage(items, items.size() == bounded ? items.get(items.size() - 1).id() : null);
    }

    @Transactional
    public CommentView addComment(CurrentUser user, long pageId, String body, Long replyTo, Long anchorId) {
        return addComment(user, pageId, body, replyTo, anchorId, List.of(), null);
    }

    @Transactional
    public CommentView addComment(CurrentUser user, long pageId, String body, Long replyTo, Long anchorId,
                                  List<Long> mentionUserIds) {
        return addComment(user, pageId, body, replyTo, anchorId, mentionUserIds, null);
    }

    @Transactional
    public CommentView addComment(CurrentUser user, long pageId, String body, Long replyTo, Long anchorId,
                                  List<Long> mentionUserIds, String idempotencyKey) {
        requireRead(user, pageId);
        if (body == null || body.isBlank()) throw new IllegalArgumentException("comment body is required");
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        if (key != null && key.length() > 120) throw new IllegalArgumentException("idempotency key is too long");
        if (key != null && !key.isBlank()) {
            List<Long> existing = jdbc.query("SELECT id FROM wiki_comment WHERE page_id = ? AND author_id = ? AND idempotency_key = ?", (rs, row) -> rs.getLong(1), pageId, user.id(), key);
            if (!existing.isEmpty()) return commentById(user, existing.get(0));
        }
        Long parentId = null;
        if (replyTo != null) {
            var target = jdbc.queryForMap("SELECT page_id, parent_id, deleted_at FROM wiki_comment WHERE id = ? FOR UPDATE", replyTo);
            if (number(target.get("page_id")) != pageId || target.get("deleted_at") != null) {
                throw new IllegalArgumentException("reply target is unavailable");
            }
            parentId = target.get("parent_id") == null ? replyTo : number(target.get("parent_id"));
            if (parentId != replyTo) jdbc.queryForMap("SELECT id FROM wiki_comment WHERE id = ? FOR UPDATE", parentId);
            if (parentId != replyTo && !exists("SELECT 1 FROM wiki_comment WHERE id = ? AND deleted_at IS NULL", parentId)) {
                throw new IllegalArgumentException("comment root is unavailable");
            }
        }
        if (anchorId != null && !exists("SELECT 1 FROM selection_anchor WHERE id = ? AND page_id = ?", anchorId, pageId)) {
            throw new IllegalArgumentException("selection anchor is unavailable");
        }
        jdbc.update("INSERT INTO wiki_comment (page_id, author_id, body, parent_id, reply_to, anchor_id, idempotency_key) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", pageId, user.id(), body.trim(), parentId, replyTo, anchorId, key == null || key.isBlank() ? null : key);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (mentions != null && id != null && mentionUserIds != null && !mentionUserIds.isEmpty()) {
            mentions.attachMentions(user, pageId, id, mentionUserIds, anchorId);
        }
        statisticsCache.incrementAfterCommit(pageId, 0, 0, 1, bumpStatsVersion(pageId));
        return commentById(user, id == null ? 0L : id);
    }

    @Transactional
    public InteractionState setCommentLike(CurrentUser user, long commentId, boolean liked) {
        long pageId = commentPage(commentId);
        requireRead(user, pageId);
        if (!exists("SELECT 1 FROM wiki_comment c LEFT JOIN wiki_comment root ON root.id = c.parent_id "
                + "WHERE c.id = ? AND c.deleted_at IS NULL AND (c.parent_id IS NULL OR root.deleted_at IS NULL)", commentId)) {
            throw new IllegalArgumentException("comment is unavailable");
        }
        if (liked) jdbc.update("INSERT IGNORE INTO comment_like (comment_id, user_id) VALUES (?, ?)", commentId, user.id());
        else jdbc.update("DELETE FROM comment_like WHERE comment_id = ? AND user_id = ?", commentId, user.id());
        return interactionState(user, pageId);
    }

    @Transactional
    public void deleteComment(CurrentUser user, long commentId) {
        var comment = jdbc.queryForMap("SELECT page_id, author_id, parent_id, deleted_at FROM wiki_comment WHERE id = ? FOR UPDATE", commentId);
        long pageId = number(comment.get("page_id"));
        requireRead(user, pageId);
        if (number(comment.get("author_id")) != user.id() && !authorization.can(user, pageId, ResourceAction.MANAGE)) {
            throw new org.springframework.security.access.AccessDeniedException("comment delete denied");
        }
        if (comment.get("deleted_at") != null) return;
        Long parentId = nullableNumber(comment.get("parent_id"));
        long rootId = parentId == null ? commentId : parentId;
        // Replies always lock their root as well. addComment takes the same root
        // lock before inserting, so a root delete and a concurrent reply cannot
        // disagree about the one aggregate delta.
        if (rootId != commentId) jdbc.queryForMap("SELECT id FROM wiki_comment WHERE id = ? FOR UPDATE", rootId);
        long visibleDelta = parentId == null
                ? count("SELECT COUNT(*) FROM wiki_comment WHERE (id = ? OR parent_id = ?) AND deleted_at IS NULL", commentId, commentId)
                : 1L;
        int changed = jdbc.update("UPDATE wiki_comment SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND deleted_at IS NULL", commentId);
        if (changed == 0) return;
        long version = bumpStatsVersion(pageId);
        statisticsCache.incrementAfterCommit(pageId, 0, 0, -visibleDelta, version);
    }

    private CommentView commentById(CurrentUser user, long commentId) {
        return jdbc.query("SELECT c.id, c.author_id, u.username, c.body, c.parent_id, c.reply_to, c.anchor_id, c.created_at, c.deleted_at, "
                        + "(SELECT COUNT(*) FROM comment_like cl WHERE cl.comment_id = c.id), "
                        + "EXISTS(SELECT 1 FROM comment_like mine WHERE mine.comment_id = c.id AND mine.user_id = ?) "
                        + "FROM wiki_comment c JOIN app_user u ON u.id = c.author_id WHERE c.id = ?",
                (rs, row) -> comment(rs), user.id(), commentId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("comment was not persisted"));
    }

    private CommentView comment(ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong(1);
        Long parent = nullableLong(rs, 5); Long reply = nullableLong(rs, 6); Long anchor = nullableLong(rs, 7);
        boolean deleted = rs.getTimestamp(9) != null;
        return new CommentView(id, rs.getLong(2), rs.getString(3), deleted ? "该评论已删除" : rs.getString(4), parent, reply, anchor,
                rs.getTimestamp(8).toInstant(), rs.getLong(10), rs.getBoolean(11), deleted);
    }

    private long commentPage(long commentId) {
        try { return jdbc.queryForObject("SELECT page_id FROM wiki_comment WHERE id = ?", Long.class, commentId); }
        catch (EmptyResultDataAccessException ex) { throw new IllegalArgumentException("comment is unavailable"); }
    }

    private void requireRead(CurrentUser user, long pageId) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        authorization.require(user, pageId, ResourceAction.READ);
    }

    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private long bumpStatsVersion(long pageId) {
        jdbc.update("INSERT INTO stats_revision (page_id, version) VALUES (?, 2) ON DUPLICATE KEY UPDATE version = version + 1", pageId);
        return currentStatsVersion(pageId);
    }
    private long currentStatsVersion(long pageId) {
        Long value = jdbc.queryForObject("SELECT version FROM stats_revision WHERE page_id = ?", Long.class, pageId);
        return value == null ? 1L : value;
    }
    private boolean exists(String sql, Object... args) { Integer value = jdbc.queryForObject(sql, Integer.class, args); return value != null; }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value)); }
    private static Long nullableNumber(Object value) { return value == null ? null : number(value); }
    private static Long nullableLong(ResultSet rs, int index) throws java.sql.SQLException { long value = rs.getLong(index); return rs.wasNull() ? null : value; }

    public record PageStatistics(long likes, long favorites, long comments) {}
    public record InteractionState(boolean liked, boolean favorite, PageStatistics statistics) {}
    public record CommentView(long id, long authorId, String authorUsername, String body, Long parentId,
                              Long replyTo, Long anchorId, Instant createdAt, long likes, boolean liked, boolean deleted) {}
    public record CommentPage(List<CommentView> items, Long nextCursor) {}
}
