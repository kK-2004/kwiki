package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.access.KnowledgeBaseRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Direct page collaborators. Knowledge-base administrators are returned as inherited members. */
@Service
public class PageCollaborationService {
    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;
    private final ScopeVersionService scopeVersions;
    private final ScopeCache scopeCache;

    public PageCollaborationService(ObjectProvider<JdbcOperations> jdbc,
                                    ResourceAuthorizationService authorization,
                                    ScopeVersionService scopeVersions,
                                    ScopeCache scopeCache) {
        this.jdbc = jdbc.getIfAvailable();
        this.authorization = authorization;
        this.scopeVersions = scopeVersions;
        this.scopeCache = scopeCache;
    }

    @Transactional(readOnly = true)
    public List<MemberView> list(CurrentUser actor, long kbId, long pageId) {
        requirePage(actor, kbId, pageId, ResourceAction.MANAGE);
        List<MemberView> result = new ArrayList<>(jdbc.query(
                "SELECT m.user_id, u.username, m.role, FALSE FROM wiki_page_member m "
                        + "JOIN app_user u ON u.id = m.user_id WHERE m.page_id = ? ORDER BY m.id",
                (rs, row) -> new MemberView(rs.getLong(1), rs.getString(2), rs.getString(3), false), pageId));
        jdbc.query("SELECT p.owner_id, u.username FROM wiki_page p JOIN app_user u ON u.id = p.owner_id WHERE p.id = ?",
                rs -> { if (rs.next()) result.add(0, new MemberView(rs.getLong(1), rs.getString(2), "OWNER", false)); return null; }, pageId);
        result.addAll(jdbc.query(
                "SELECT m.user_id, u.username, m.role, TRUE FROM wiki_page p "
                        + "JOIN knowledge_base_member m ON m.kb_id = p.kb_id AND m.role IN ('OWNER','ADMIN') "
                        + "JOIN app_user u ON u.id = m.user_id "
                        + "LEFT JOIN wiki_page_member pm ON pm.page_id = p.id AND pm.user_id = m.user_id "
                        + "WHERE p.id = ? AND p.owner_id <> m.user_id AND pm.id IS NULL ORDER BY m.user_id",
                (rs, row) -> new MemberView(rs.getLong(1), rs.getString(2), rs.getString(3), true), pageId));
        return result;
    }

    @Transactional
    public MemberView upsert(CurrentUser actor, long kbId, long pageId, long userId, String requestedRole) {
        requirePage(actor, kbId, pageId, ResourceAction.MANAGE);
        String role = normalizeRole(requestedRole);
        long ownerId = owner(pageId);
        if (ownerId == userId || "OWNER".equals(role)) throw new AccessDeniedException("the page owner uses the transfer flow");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_active FROM app_user WHERE id = ?", Boolean.class, userId))) {
            throw new IllegalArgumentException("user is unavailable");
        }
        if ("ADMIN".equals(role) && !actor.admin() && actor.id() != ownerId) {
            throw new AccessDeniedException("only the page owner can appoint administrators");
        }
        String oldRole = jdbc.query("SELECT role FROM wiki_page_member WHERE page_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, pageId, userId);
        if ("ADMIN".equals(oldRole) && !"ADMIN".equals(role) && !actor.admin() && actor.id() != ownerId) {
            throw new AccessDeniedException("only the page owner can demote administrators");
        }
        jdbc.update("INSERT INTO wiki_page_member (page_id, user_id, role, created_by) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE role = VALUES(role), updated_at = CURRENT_TIMESTAMP(6)",
                pageId, userId, role, actor.id());
        changed(kbId, userId);
        audit(actor.id(), pageId, "PAGE_MEMBER_ROLE_CHANGED", userId, "{\"role\":\"" + role + "\"}");
        String username = jdbc.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, userId);
        return new MemberView(userId, username, role, false);
    }

    @Transactional
    public void remove(CurrentUser actor, long kbId, long pageId, long userId) {
        requirePage(actor, kbId, pageId, ResourceAction.MANAGE);
        long ownerId = owner(pageId);
        if (ownerId == userId) throw new AccessDeniedException("the page owner must transfer ownership");
        String role = jdbc.query("SELECT role FROM wiki_page_member WHERE page_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, pageId, userId);
        if ("ADMIN".equals(role) && !actor.admin() && actor.id() != ownerId) {
            throw new AccessDeniedException("only the page owner can remove administrators");
        }
        if (jdbc.update("DELETE FROM wiki_page_member WHERE page_id = ? AND user_id = ?", pageId, userId) > 0) {
            changed(kbId, userId);
            audit(actor.id(), pageId, "PAGE_MEMBER_REMOVED", userId, null);
        }
    }

    private void requirePage(CurrentUser actor, long kbId, long pageId, ResourceAction action) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        authorization.requireInKnowledgeBase(actor, kbId, pageId, action);
    }
    private long owner(long pageId) { Long value = jdbc.queryForObject("SELECT owner_id FROM wiki_page WHERE id = ?", Long.class, pageId); if (value == null) throw new IllegalArgumentException("page not found"); return value; }
    private void changed(long kbId, long userId) { scopeVersions.bump(kbId); scopeCache.invalidate(userId); }
    private void audit(long actorId, long pageId, String action, long target, String details) { jdbc.update("INSERT INTO management_audit (actor_id, resource_type, resource_id, action, target_user_id, details_json) VALUES (?, 'PAGE', ?, ?, ?, ?)", actorId, pageId, action, target, details); }
    private static String normalizeRole(String role) { String value = role == null ? "" : role.trim().toUpperCase(); if (!List.of("VIEWER", "EDITOR", "ADMIN").contains(value)) throw new IllegalArgumentException("invalid page member role"); return value; }
    public record MemberView(long userId, String username, String role, boolean inherited) {}
}
