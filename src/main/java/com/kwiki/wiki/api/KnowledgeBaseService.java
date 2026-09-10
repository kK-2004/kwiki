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
 * 知识库生命周期与成员管理。每次读取都要求调用方
 * 位于该知识库之内（管理员除外）；成员关系变更会递增
 * 知识库的作用域版本并使缓存的作用域失效，因此在旧作用域下
 * 进行中的请求会被出站守卫中止。
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeBaseMemberRepository members;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ScopeVersionService scopeVersions;
    private final ScopeCache scopeCache;
    private final com.kwiki.wiki.archive.ResourceArchiveService archiveService;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases,
                                KnowledgeBaseMemberRepository members,
                                KnowledgeBaseAuthorizationService authorization,
                                ScopeVersionService scopeVersions,
                                ScopeCache scopeCache) {
        this(knowledgeBases, members, authorization, scopeVersions, scopeCache, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases,
                                KnowledgeBaseMemberRepository members,
                                KnowledgeBaseAuthorizationService authorization,
                                ScopeVersionService scopeVersions,
                                ScopeCache scopeCache,
                                com.kwiki.wiki.archive.ResourceArchiveService archiveService) {
        this.knowledgeBases = knowledgeBases;
        this.members = members;
        this.authorization = authorization;
        this.scopeVersions = scopeVersions;
        this.scopeCache = scopeCache;
        this.archiveService = archiveService;
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

    /** 调用方可见的知识库：普通用户看自己的成员关系，管理员看全部。 */
    public List<KnowledgeBase> listAccessible(CurrentUser user) {
        if (user.admin()) {
            return knowledgeBases.findByStatusOrderByNameAsc(KnowledgeBase.STATUS_ACTIVE);
        }
        List<Long> kbIds = members.findByUserId(user.id()).stream()
                .map(KnowledgeBaseMember::getKbId)
                .toList();
        return knowledgeBases.findByIdInAndStatusOrderByNameAsc(kbIds, KnowledgeBase.STATUS_ACTIVE);
    }

    /** 加载知识库，对不可访问的库以 404 隐藏其是否存在。 */
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

    /**
     * 委托给统一的回收站服务：对所有有效的
     * 页面/附件/源文档分批处理，保留 7 天，立即隔离索引。
     */
    public void archive(CurrentUser user, long kbId) {
        if (archiveService != null) {
            archiveService.archiveKnowledgeBase(user, kbId);
            return;
        }
        // 未接入回收站的测试/兜底路径。
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
