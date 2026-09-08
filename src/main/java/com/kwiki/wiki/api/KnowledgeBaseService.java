package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.KnowledgeBaseRole;
import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

/**
 * Knowledge-base lifecycle and membership management. Every read requires the caller
 * to be inside the knowledge base (admins excepted); membership mutations bump the
 * knowledge-base scope version and invalidate cached scopes so in-flight requests
 * under the old scope are aborted by the outbound guard.
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeBaseMemberRepository members;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ScopeVersionService scopeVersions;
    private final ScopeCache scopeCache;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases,
                                KnowledgeBaseMemberRepository members,
                                KnowledgeBaseAuthorizationService authorization,
                                ScopeVersionService scopeVersions,
                                ScopeCache scopeCache) {
        this.knowledgeBases = knowledgeBases;
        this.members = members;
        this.authorization = authorization;
        this.scopeVersions = scopeVersions;
        this.scopeCache = scopeCache;
    }

    @Transactional
    public KnowledgeBase create(CurrentUser creator, String name, String description) {
        KnowledgeBase kb = knowledgeBases.save(
                new KnowledgeBase(UUID.randomUUID().toString(), name, description, creator.id()));
        members.save(new KnowledgeBaseMember(kb.getId(), creator.id(),
                KnowledgeBaseRole.OWNER, creator.id()));
        scopeVersions.bump(kb.getId());
        return kb;
    }

    /** Knowledge bases visible to the caller: memberships for users, everything for admins. */
    public List<KnowledgeBase> listAccessible(CurrentUser user) {
        if (user.admin()) {
            return knowledgeBases.findByStatusOrderByNameAsc(KnowledgeBase.STATUS_ACTIVE);
        }
        List<Long> kbIds = members.findByUserId(user.id()).stream()
                .map(KnowledgeBaseMember::getKbId)
                .toList();
        return knowledgeBases.findByIdInAndStatusOrderByNameAsc(kbIds, KnowledgeBase.STATUS_ACTIVE);
    }

    /** Loads a knowledge base, hiding existence of inaccessible ones behind 404. */
    public KnowledgeBase requireAccessible(CurrentUser user, long kbId) {
        KnowledgeBase kb = knowledgeBases.findById(kbId)
                .filter(candidate -> !candidate.isArchived())
                .orElseThrow(() -> new NotFoundException("knowledge base not found"));
        if (!authorization.can(user, kbId, WikiAction.READ_PAGE)) {
            throw new NotFoundException("knowledge base not found");
        }
        return kb;
    }

    public boolean canManage(CurrentUser user, long kbId) {
        return authorization.can(user, kbId, WikiAction.MANAGE_MEMBERS);
    }

    public boolean canUpload(CurrentUser user, long kbId) {
        return authorization.can(user, kbId, WikiAction.UPLOAD_ATTACHMENT);
    }

    @Transactional
    public KnowledgeBase update(CurrentUser user, long kbId, String name, String description) {
        authorization.require(user, kbId, WikiAction.UPDATE_KNOWLEDGE_BASE);
        KnowledgeBase kb = requireAccessible(user, kbId);
        kb.update(name, description);
        return knowledgeBases.save(kb);
    }

    @Transactional
    public void archive(CurrentUser user, long kbId) {
        authorization.require(user, kbId, WikiAction.ARCHIVE_KNOWLEDGE_BASE);
        KnowledgeBase kb = requireAccessible(user, kbId);
        kb.archive();
        knowledgeBases.save(kb);
    }

    public List<KnowledgeBaseMember> listMembers(CurrentUser user, long kbId) {
        requireAccessible(user, kbId);
        return members.findByKbIdOrderByIdAsc(kbId);
    }

    @Transactional
    public KnowledgeBaseMember upsertMember(CurrentUser actor, long kbId, long userId,
                                            KnowledgeBaseRole role) {
        authorization.require(actor, kbId, WikiAction.MANAGE_MEMBERS);
        requireAccessible(actor, kbId);
        if (role == KnowledgeBaseRole.OWNER) {
            throw new AccessDeniedException("ownership changes use the transfer flow");
        }
        KnowledgeBaseRole actorRole = members.findByKbIdAndUserId(kbId, actor.id())
                .map(KnowledgeBaseMember::getRole).orElse(null);
        if (role == KnowledgeBaseRole.ADMIN && !actor.admin() && actorRole != KnowledgeBaseRole.OWNER) {
            throw new AccessDeniedException("only the knowledge-base owner can appoint administrators");
        }

        KnowledgeBaseMember member = members.findByKbIdAndUserId(kbId, userId)
                .orElseGet(() -> new KnowledgeBaseMember(kbId, userId, role, actor.id()));
        if (member.getRole() == KnowledgeBaseRole.OWNER) {
            throw new AccessDeniedException("the knowledge-base owner must transfer ownership");
        }
        if (member.getRole() == KnowledgeBaseRole.ADMIN && !actor.admin() && actorRole != KnowledgeBaseRole.OWNER) {
            throw new AccessDeniedException("only the owner can change administrators");
        }
        member.changeRole(role);
        KnowledgeBaseMember saved = members.save(member);

        scopeVersions.bump(kbId);
        scopeCache.invalidate(userId);
        return saved;
    }

    @Transactional
    public void removeMember(CurrentUser actor, long kbId, long userId) {
        authorization.require(actor, kbId, WikiAction.MANAGE_MEMBERS);
        requireAccessible(actor, kbId);
        KnowledgeBaseRole actorRole = members.findByKbIdAndUserId(kbId, actor.id())
                .map(KnowledgeBaseMember::getRole).orElse(null);
        KnowledgeBaseRole targetRole = members.findByKbIdAndUserId(kbId, userId)
                .map(KnowledgeBaseMember::getRole).orElse(null);
        if (targetRole == KnowledgeBaseRole.OWNER) {
            throw new AccessDeniedException("the knowledge-base owner must transfer ownership");
        }
        if (targetRole == KnowledgeBaseRole.ADMIN && !actor.admin() && actorRole != KnowledgeBaseRole.OWNER) {
            throw new AccessDeniedException("only the knowledge-base owner can remove administrators");
        }
        members.deleteByKbIdAndUserId(kbId, userId);
        scopeVersions.bump(kbId);
        scopeCache.invalidate(userId);
    }
}
