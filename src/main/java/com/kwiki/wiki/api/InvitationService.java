package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.KnowledgeBaseRole;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.infrastructure.redis.ScopeCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Creates and accepts high entropy, resource-scoped invitations. */
@Service
public class InvitationService {
    private final JdbcOperations jdbc;
    private final KnowledgeBaseAuthorizationService knowledgeBases;
    private final ResourceAuthorizationService pages;
    private final ScopeVersionService scopeVersions;
    private final ScopeCache scopeCache;

    public InvitationService(ObjectProvider<JdbcOperations> jdbc,
                             KnowledgeBaseAuthorizationService knowledgeBases,
                             ResourceAuthorizationService pages,
                             ScopeVersionService scopeVersions,
                             ScopeCache scopeCache) {
        this.jdbc = jdbc.getIfAvailable(); this.knowledgeBases = knowledgeBases; this.pages = pages; this.scopeVersions = scopeVersions; this.scopeCache = scopeCache;
    }

    @Transactional
    public InvitationCreated create(CurrentUser actor, String resourceType, long resourceId, String role, Duration requestedTtl) {
        requireDb();
        String type = normalizeType(resourceType); String normalizedRole = normalizeRole(role);
        requireManage(actor, type, resourceId);
        Duration ttl = requestedTtl == null ? Duration.ofDays(7) : requestedTtl;
        if (ttl.compareTo(Duration.ofHours(1)) < 0 || ttl.compareTo(Duration.ofDays(30)) > 0) throw new IllegalArgumentException("invalid invitation expiry");
        String token = UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
        String hash = hash(token);
        Instant expires = Instant.now().plus(ttl);
        jdbc.update("INSERT INTO resource_invitation (resource_type, resource_id, token_hash, issued_by, role, expires_at) VALUES (?, ?, ?, ?, ?, ?)", type, resourceId, hash, actor.id(), normalizedRole, expires);
        return new InvitationCreated(token, type, resourceId, normalizedRole, expires);
    }

    @Transactional(readOnly = true)
    public InvitationView preview(CurrentUser user, String token) {
        requireDb();
        var invitation = find(token);
        if (!valid(invitation) || !issuerCanShare(invitation)) throw new IllegalArgumentException("invitation is invalid or expired");
        return new InvitationView(invitation.id(), invitation.resourceType(), invitation.resourceId(), invitation.role(), invitation.expiresAt(), "PENDING");
    }

    @Transactional
    public Acceptance accept(CurrentUser user, String token) {
        requireDb(); var invitation = find(token);
        if (!valid(invitation) || !issuerCanShare(invitation)) throw new IllegalArgumentException("invitation is invalid or expired");
        if (alreadyGranted(invitation.resourceType(), invitation.resourceId(), user.id())) {
            return new Acceptance("ACCEPTED", invitation.resourceType(), invitation.resourceId(), invitation.role());
        }
        boolean approvalRequired = approvalRequired(invitation.resourceType(), invitation.resourceId());
        if (approvalRequired) {
            jdbc.update("INSERT IGNORE INTO resource_join_request (invitation_id, resource_type, resource_id, user_id, role) VALUES (?, ?, ?, ?, ?)", invitation.id(), invitation.resourceType(), invitation.resourceId(), user.id(), invitation.role());
            return new Acceptance("PENDING", invitation.resourceType(), invitation.resourceId(), invitation.role());
        }
        grant(invitation.resourceType(), invitation.resourceId(), user.id(), invitation.role(), user.id());
        long kbId = "KB".equals(invitation.resourceType()) ? invitation.resourceId() : jdbc.queryForObject("SELECT kb_id FROM wiki_page WHERE id = ?", Long.class, invitation.resourceId());
        scopeVersions.bump(kbId); scopeCache.invalidate(user.id());
        return new Acceptance("ACCEPTED", invitation.resourceType(), invitation.resourceId(), invitation.role());
    }

    @Transactional
    public void revoke(CurrentUser actor, long invitationId) {
        requireDb(); var row = jdbc.queryForMap("SELECT resource_type, resource_id FROM resource_invitation WHERE id = ?", invitationId);
        requireManage(actor, String.valueOf(row.get("resource_type")), number(row.get("resource_id")));
        jdbc.update("UPDATE resource_invitation SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP(6)) WHERE id = ?", invitationId);
    }

    @Transactional(readOnly = true)
    public List<JoinRequestView> requests(CurrentUser actor, String resourceType, long resourceId) {
        requireDb(); String type = normalizeType(resourceType); requireManage(actor, type, resourceId);
        return jdbc.query("SELECT r.id, r.user_id, u.username, r.role, r.status, r.created_at FROM resource_join_request r JOIN app_user u ON u.id = r.user_id WHERE r.resource_type = ? AND r.resource_id = ? ORDER BY r.created_at DESC, r.id DESC", (rs, row) -> new JoinRequestView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), type, resourceId);
    }

    @Transactional(readOnly = true)
    public List<InvitationListView> list(CurrentUser actor, String resourceType, long resourceId) {
        requireDb(); String type = normalizeType(resourceType); requireManage(actor, type, resourceId);
        return jdbc.query("SELECT id, role, expires_at, revoked_at, created_at FROM resource_invitation WHERE resource_type = ? AND resource_id = ? ORDER BY created_at DESC, id DESC",
                (rs, row) -> new InvitationListView(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), type, resourceId);
    }

    @Transactional
    public void review(CurrentUser actor, long requestId, boolean approve) {
        requireDb();
        var row = jdbc.queryForMap("SELECT invitation_id, resource_type, resource_id, user_id, role, status FROM resource_join_request WHERE id = ? FOR UPDATE", requestId);
        String type = String.valueOf(row.get("resource_type")); long resourceId = number(row.get("resource_id")); requireManage(actor, type, resourceId);
        if (!"PENDING".equals(row.get("status"))) throw new IllegalArgumentException("join request already reviewed");
        long userId = number(row.get("user_id"));
        if (approve) {
            var invitation = jdbc.queryForMap("SELECT expires_at, revoked_at FROM resource_invitation WHERE id = ?", row.get("invitation_id"));
            if (invitation.get("revoked_at") != null || !((java.sql.Timestamp) invitation.get("expires_at")).toInstant().isAfter(Instant.now())) throw new IllegalArgumentException("invitation is no longer valid");
            grant(type, resourceId, userId, String.valueOf(row.get("role")), actor.id());
            long kbId = "KB".equals(type) ? resourceId : jdbc.queryForObject("SELECT kb_id FROM wiki_page WHERE id = ?", Long.class, resourceId);
            scopeVersions.bump(kbId); scopeCache.invalidate(userId);
        }
        jdbc.update("UPDATE resource_join_request SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND status = 'PENDING'", approve ? "APPROVED" : "REJECTED", actor.id(), requestId);
    }

    private void grant(String type, long resourceId, long userId, String role, long createdBy) {
        if ("KB".equals(type)) {
            var existing = jdbc.query("SELECT role FROM knowledge_base_member WHERE kb_id = ? AND user_id = ?", (rs, row) -> rs.getString(1), resourceId, userId);
            if (existing.isEmpty() || rank(role) > rank(existing.get(0))) jdbc.update("INSERT INTO knowledge_base_member (kb_id, user_id, role, created_by) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE role = VALUES(role)", resourceId, userId, role, createdBy);
        } else {
            var existing = jdbc.query("SELECT role FROM wiki_page_member WHERE page_id = ? AND user_id = ?", (rs, row) -> rs.getString(1), resourceId, userId);
            if (existing.isEmpty() || rank(role) > rank(existing.get(0))) jdbc.update("INSERT INTO wiki_page_member (page_id, user_id, role, created_by) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE role = VALUES(role)", resourceId, userId, role, createdBy);
        }
    }

    private boolean approvalRequired(String type, long id) {
        Long kbId = "KB".equals(type) ? id : jdbc.queryForObject("SELECT kb_id FROM wiki_page WHERE id = ?", Long.class, id);
        Boolean required = jdbc.queryForObject("SELECT join_approval_required FROM knowledge_base WHERE id = ?", Boolean.class, kbId);
        return required == null || required;
    }

    private boolean alreadyGranted(String type, long id, long userId) {
        String table = "KB".equals(type) ? "knowledge_base_member" : "wiki_page_member";
        String key = "KB".equals(type) ? "kb_id" : "page_id";
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + key + " = ? AND user_id = ?", Integer.class, id, userId);
        return count != null && count > 0;
    }

    private void requireManage(CurrentUser actor, String type, long id) {
        if ("KB".equals(type)) knowledgeBases.require(actor, id, com.kwiki.wiki.access.WikiAction.MANAGE_MEMBERS);
        else pages.require(actor, id, ResourceAction.MANAGE);
    }

    private InvitationRow find(String token) {
        try { return jdbc.query("SELECT id, resource_type, resource_id, role, expires_at, revoked_at, issued_by FROM resource_invitation WHERE token_hash = ?", (rs, row) -> new InvitationRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getLong(7)), hash(token)).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("invitation is invalid or expired")); }
        catch (EmptyResultDataAccessException ex) { throw new IllegalArgumentException("invitation is invalid or expired"); }
    }

    private boolean valid(InvitationRow row) { return row.revokedAt() == null && row.expiresAt().isAfter(Instant.now()); }
    private boolean issuerCanShare(InvitationRow row) {
        Boolean admin = jdbc.queryForObject("SELECT is_admin FROM app_user WHERE id = ? AND is_active = TRUE", Boolean.class, row.issuedBy());
        if (Boolean.TRUE.equals(admin)) return true;
        if ("KB".equals(row.resourceType())) {
            String role = jdbc.query("SELECT role FROM knowledge_base_member WHERE kb_id = ? AND user_id = ?", (rs, n) -> rs.getString(1), row.resourceId(), row.issuedBy()).stream().findFirst().orElse(null);
            return "OWNER".equals(role) || "ADMIN".equals(role);
        }
        return pages.can(new CurrentUser(row.issuedBy(), "", false), row.resourceId(), ResourceAction.MANAGE);
    }
    private void requireDb() { if (jdbc == null) throw new IllegalStateException("database is unavailable"); }
    private static String normalizeType(String type) { String value = type == null ? "" : type.trim().toUpperCase(); if (!List.of("KB", "PAGE").contains(value)) throw new IllegalArgumentException("invalid resource type"); return value; }
    private static String normalizeRole(String role) { String value = role == null ? "" : role.trim().toUpperCase(); if (!List.of("VIEWER", "EDITOR").contains(value)) throw new IllegalArgumentException("invalid invitation role"); return value; }
    private static int rank(String role) { return "EDITOR".equals(role) ? 2 : 1; }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value)); }
    private static String hash(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }

    private record InvitationRow(long id, String resourceType, long resourceId, String role, Instant expiresAt, Instant revokedAt, long issuedBy) {}
    public record InvitationCreated(String token, String resourceType, long resourceId, String role, Instant expiresAt) {}
    public record InvitationView(long id, String resourceType, long resourceId, String role, Instant expiresAt, String status) {}
    public record Acceptance(String status, String resourceType, long resourceId, String role) {}
    public record JoinRequestView(long id, long userId, String username, String role, String status, Instant createdAt) {}
    public record InvitationListView(long id, String role, Instant expiresAt, Instant revokedAt, Instant createdAt) {}
}
