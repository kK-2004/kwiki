package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
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

/** CAS 保护下的创建者转让；历史的 created_by 值保持不变。 */
@Service
public class OwnershipTransferService {
    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService pages;
    private final ScopeVersionService scopeVersions;
    private final ScopeCache scopeCache;

    public OwnershipTransferService(ObjectProvider<JdbcOperations> jdbc, ResourceAuthorizationService pages, ScopeVersionService scopeVersions, ScopeCache scopeCache) {
        this.jdbc = jdbc.getIfAvailable(); this.pages = pages; this.scopeVersions = scopeVersions; this.scopeCache = scopeCache;
    }

    @Transactional
    public TransferCreated create(CurrentUser actor, String type, long resourceId, long recipientId, Duration ttl) {
        requireDb(); String resourceType = normalize(type);
        long ownerId = owner(resourceType, resourceId);
        if (ownerId != actor.id()) throw new AccessDeniedException("only the resource owner can transfer ownership");
        if (recipientId == actor.id()) throw new IllegalArgumentException("recipient must be different");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_active FROM app_user WHERE id = ?", Boolean.class, recipientId))) throw new IllegalArgumentException("recipient is unavailable");
        Duration actual = ttl == null ? Duration.ofDays(7) : ttl;
        if (actual.compareTo(Duration.ofHours(1)) < 0 || actual.compareTo(Duration.ofDays(30)) > 0) throw new IllegalArgumentException("invalid transfer expiry");
        String token = UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
        Instant expires = Instant.now().plus(actual);
        jdbc.update("INSERT INTO ownership_transfer (resource_type, resource_id, from_user_id, to_user_id, token_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?)", resourceType, resourceId, actor.id(), recipientId, hash(token), expires);
        audit(actor.id(), resourceType, resourceId, "OWNERSHIP_TRANSFER_CREATED", recipientId,
                "{\"expiresAt\":\"" + expires + "\"}");
        return new TransferCreated(token, resourceType, resourceId, recipientId, expires);
    }

    @Transactional
    public void accept(CurrentUser recipient, String token) {
        requireDb(); TransferRow transfer = find(token);
        if (!"PENDING".equals(transfer.status()) || !transfer.expiresAt().isAfter(Instant.now())) throw new IllegalArgumentException("transfer is invalid or expired");
        if (transfer.toUserId() != recipient.id()) throw new AccessDeniedException("transfer recipient mismatch");
        long currentOwner = owner(transfer.resourceType(), transfer.resourceId());
        if (currentOwner != transfer.fromUserId()) throw new IllegalArgumentException("owner has changed");
        if ("KB".equals(transfer.resourceType())) {
            Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base_member WHERE kb_id = ? AND user_id = ?", Integer.class, transfer.resourceId(), recipient.id());
            if (member == null || member == 0) throw new AccessDeniedException("recipient is not a knowledge-base collaborator");
            int changed = jdbc.update("UPDATE knowledge_base SET owner_id = ? WHERE id = ? AND owner_id = ?", recipient.id(), transfer.resourceId(), transfer.fromUserId());
            if (changed != 1) throw new IllegalArgumentException("owner has changed");
            jdbc.update("UPDATE knowledge_base_member SET role = 'EDITOR' WHERE kb_id = ? AND user_id = ? AND role = 'OWNER'", transfer.resourceId(), transfer.fromUserId());
            jdbc.update("INSERT INTO knowledge_base_member (kb_id, user_id, role, created_by) VALUES (?, ?, 'OWNER', ?) ON DUPLICATE KEY UPDATE role = 'OWNER'", transfer.resourceId(), recipient.id(), recipient.id());
        } else {
            if (!pages.can(recipient, transfer.resourceId(), com.kwiki.wiki.access.ResourceAction.READ)) throw new AccessDeniedException("recipient is not a page collaborator");
            int changed = jdbc.update("UPDATE wiki_page SET owner_id = ? WHERE id = ? AND owner_id = ?", recipient.id(), transfer.resourceId(), transfer.fromUserId());
            if (changed != 1) throw new IllegalArgumentException("owner has changed");
            jdbc.update("INSERT INTO wiki_page_member (page_id, user_id, role, created_by) VALUES (?, ?, 'EDITOR', ?) ON DUPLICATE KEY UPDATE role = 'EDITOR'", transfer.resourceId(), transfer.fromUserId(), transfer.fromUserId());
        }
        if (jdbc.update("UPDATE ownership_transfer SET status = 'ACCEPTED', accepted_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND status = 'PENDING'", transfer.id()) != 1) throw new IllegalArgumentException("transfer was already accepted");
        long kbId = "KB".equals(transfer.resourceType()) ? transfer.resourceId() : jdbc.queryForObject("SELECT kb_id FROM wiki_page WHERE id = ?", Long.class, transfer.resourceId());
        scopeVersions.bump(kbId); scopeCache.invalidate(transfer.fromUserId()); scopeCache.invalidate(transfer.toUserId());
        audit(recipient.id(), transfer.resourceType(), transfer.resourceId(), "OWNERSHIP_TRANSFER_ACCEPTED",
                transfer.fromUserId(), "{\"fromUserId\":" + transfer.fromUserId() + "}");
    }

    @Transactional
    public void revoke(CurrentUser actor, long transferId) {
        requireDb(); TransferRow row = jdbc.query("SELECT id, resource_type, resource_id, from_user_id, to_user_id, expires_at, status FROM ownership_transfer WHERE id = ?", (rs, n) -> new TransferRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getTimestamp(6).toInstant(), rs.getString(7)), transferId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("transfer not found"));
        if (row.fromUserId() != actor.id()) throw new AccessDeniedException("transfer revoke denied");
        if (jdbc.update("UPDATE ownership_transfer SET status = 'REVOKED' WHERE id = ? AND status = 'PENDING'", transferId) == 1) {
            audit(actor.id(), row.resourceType(), row.resourceId(), "OWNERSHIP_TRANSFER_REVOKED", row.toUserId(), null);
        }
    }

    private void audit(long actorId, String resourceType, long resourceId, String action, Long targetUserId, String details) {
        jdbc.update("INSERT INTO management_audit (actor_id, resource_type, resource_id, action, target_user_id, details_json) VALUES (?, ?, ?, ?, ?, ?)",
                actorId, resourceType, resourceId, action, targetUserId, details);
    }

    private long owner(String type, long id) { Long value = jdbc.queryForObject("SELECT owner_id FROM " + ("KB".equals(type) ? "knowledge_base" : "wiki_page") + " WHERE id = ?", Long.class, id); if (value == null) throw new IllegalArgumentException("resource not found"); return value; }
    private TransferRow find(String token) { try { return jdbc.query("SELECT id, resource_type, resource_id, from_user_id, to_user_id, expires_at, status FROM ownership_transfer WHERE token_hash = ? FOR UPDATE", (rs, n) -> new TransferRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getTimestamp(6).toInstant(), rs.getString(7)), hash(token)).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("transfer not found")); } catch (EmptyResultDataAccessException ex) { throw new IllegalArgumentException("transfer not found"); } }
    private void requireDb() { if (jdbc == null) throw new IllegalStateException("database is unavailable"); }
    private static String normalize(String type) { String value = type == null ? "" : type.trim().toUpperCase(); if (!List.of("KB", "PAGE").contains(value)) throw new IllegalArgumentException("invalid resource type"); return value; }
    private static String hash(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private record TransferRow(long id, String resourceType, long resourceId, long fromUserId, long toUserId, Instant expiresAt, String status) {}
    public record TransferCreated(String token, String resourceType, long resourceId, long recipientId, Instant expiresAt) {}
}
