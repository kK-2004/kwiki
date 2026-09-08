package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.infrastructure.redis.ScopeCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Atomic document audience changes with source-membership revalidation. */
@Service
public class ResourceAudienceService {
    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;
    private final ScopeVersionService scopeVersions;
    private final ScopeCache scopeCache;
    private final KnowledgeBaseAuthorizationService knowledgeBases;

    public ResourceAudienceService(ObjectProvider<JdbcOperations> jdbc, ResourceAuthorizationService authorization, ScopeVersionService scopeVersions, ScopeCache scopeCache) { this(jdbc, authorization, scopeVersions, scopeCache, null); }

    @org.springframework.beans.factory.annotation.Autowired
    public ResourceAudienceService(ObjectProvider<JdbcOperations> jdbc, ResourceAuthorizationService authorization, ScopeVersionService scopeVersions, ScopeCache scopeCache, KnowledgeBaseAuthorizationService knowledgeBases) { this.jdbc = jdbc.getIfAvailable(); this.authorization = authorization; this.scopeVersions = scopeVersions; this.scopeCache = scopeCache; this.knowledgeBases = knowledgeBases; }

    @Transactional(readOnly = true)
    public AudienceView get(CurrentUser user, long pageId) {
        // Audience membership is management metadata; exposing it to a reader
        // would disclose the selected users even when the page body is private.
        requireDb(); authorization.require(user, pageId, ResourceAction.MANAGE);
        String mode = jdbc.queryForObject("SELECT audience_mode FROM wiki_page WHERE id = ?", String.class, pageId);
        List<Member> members = jdbc.query("SELECT source_kb_id, user_id FROM wiki_page_audience_member WHERE page_id = ? ORDER BY source_kb_id, user_id", (rs, row) -> new Member(rs.getLong(1), rs.getLong(2)), pageId);
        return new AudienceView(mode, members);
    }

    @Transactional
    public AudienceView update(CurrentUser user, long pageId, String mode, List<Member> requested) {
        requireDb(); authorization.require(user, pageId, ResourceAction.MANAGE);
        String normalized = mode == null ? "" : mode.trim().toUpperCase();
        if (!List.of("PRIVATE", "SELECTED_MEMBERS", "KB_MEMBERS").contains(normalized)) throw new IllegalArgumentException("invalid audience mode");
        List<Member> members = requested == null ? List.of() : requested.stream().distinct().toList();
        if ("SELECTED_MEMBERS".equals(normalized)) {
            for (Member member : members) {
                Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base_member WHERE kb_id = ? AND user_id = ?", Integer.class, member.sourceKbId(), member.userId());
                if (valid == null || valid == 0) throw new IllegalArgumentException("audience member is no longer in source knowledge base");
            }
        } else if (!members.isEmpty()) throw new IllegalArgumentException("members are only valid for selected audience");
        Long kbId = jdbc.queryForObject("SELECT kb_id FROM wiki_page WHERE id = ?", Long.class, pageId);
        List<Long> previousUsers = jdbc.query("SELECT user_id FROM wiki_page_audience_member WHERE page_id = ?", (rs, row) -> rs.getLong(1), pageId);
        jdbc.update("UPDATE wiki_page SET audience_mode = ? WHERE id = ?", normalized, pageId);
        jdbc.update("DELETE FROM wiki_page_audience_member WHERE page_id = ?", pageId);
        for (Member member : members) jdbc.update("INSERT INTO wiki_page_audience_member (page_id, source_kb_id, user_id, created_by) VALUES (?, ?, ?, ?)", pageId, member.sourceKbId(), member.userId(), user.id());
        if ("PRIVATE".equals(normalized)) {
            jdbc.update("DELETE FROM wiki_page_member WHERE page_id = ?", pageId);
            jdbc.update("UPDATE resource_invitation SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP(6)) WHERE resource_type = 'PAGE' AND resource_id = ? AND revoked_at IS NULL", pageId);
            jdbc.update("UPDATE resource_join_request SET status = 'REJECTED' WHERE resource_type = 'PAGE' AND resource_id = ? AND status = 'PENDING'", pageId);
        }
        if (kbId != null) scopeVersions.bump(kbId);
        previousUsers.forEach(scopeCache::invalidate);
        members.forEach(member -> scopeCache.invalidate(member.userId()));
        return get(user, pageId);
    }

    @Transactional(readOnly = true)
    public List<User> candidates(CurrentUser user, long pageId, long sourceKbId, String query, int limit) {
        requireDb(); authorization.require(user, pageId, ResourceAction.MANAGE);
        return queryCandidates(sourceKbId, query, limit);
    }

    @Transactional(readOnly = true)
    public List<User> candidatesInKnowledgeBase(CurrentUser user, long kbId, long sourceKbId, String query, int limit) {
        requireDb();
        if (knowledgeBases == null) throw new IllegalStateException("authorization is unavailable");
        knowledgeBases.require(user, kbId, WikiAction.MANAGE_MEMBERS);
        if (!knowledgeBases.can(user, sourceKbId, WikiAction.READ_PAGE)) throw new org.springframework.security.access.AccessDeniedException("source knowledge base access denied");
        return queryCandidates(sourceKbId, query, limit);
    }

    private List<User> queryCandidates(long sourceKbId, String query, int limit) {
        String prefix = query == null ? "" : query.trim().toLowerCase(); String escaped = escapeLike(prefix); int bounded = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        return jdbc.query("SELECT u.id, u.username, u.display_name FROM app_user u JOIN knowledge_base_member m ON m.user_id = u.id AND m.kb_id = ? WHERE u.is_active = TRUE AND LOWER(u.username) LIKE ? ESCAPE '\\\\' ORDER BY CASE WHEN LOWER(u.username) = ? THEN 0 WHEN LOWER(u.username) LIKE ? ESCAPE '\\\\' THEN 1 ELSE 2 END, u.username, u.id LIMIT ?", (rs, row) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)), sourceKbId, escaped.isEmpty() ? "%" : escaped + "%", prefix, escaped.isEmpty() ? "%" : escaped + "%", bounded);
    }

    private String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    private void requireDb() { if (jdbc == null) throw new IllegalStateException("database is unavailable"); }
    public record Member(long sourceKbId, long userId) {}
    public record AudienceView(String mode, List<Member> members) {}
    public record User(long id, String username, String displayName) {}
}
