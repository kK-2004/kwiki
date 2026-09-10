package com.kwiki.wiki.access;

import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析每次请求一次的不可变授权作用域：成员知识库及其当前作用域版本（管理员即超级用户）。
 * 经由 Redis 作用域缓存；每条缓存路径都会降级为直接解析，而不会扩大范围。
 */
@Component
public class AuthorizationScopeResolver {

    private final KnowledgeBaseMemberRepository members;
    private final ScopeVersionService scopeVersions;
    private final ScopeCache scopeCache;
    private final JdbcOperations jdbc;

    public AuthorizationScopeResolver(KnowledgeBaseMemberRepository members,
                                      ScopeVersionService scopeVersions,
                                      ScopeCache scopeCache) {
        this(members, scopeVersions, scopeCache, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthorizationScopeResolver(KnowledgeBaseMemberRepository members,
                                      ScopeVersionService scopeVersions,
                                      ScopeCache scopeCache,
                                      ObjectProvider<JdbcOperations> jdbc) {
        this.members = members;
        this.scopeVersions = scopeVersions;
        this.scopeCache = scopeCache;
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
    }

    public AuthorizationScope resolve(CurrentUser user) {
        if (user.admin()) {
            return new AuthorizationScope(user.id(), true, Set.of(), Map.of());
        }
        return scopeCache.getOrLoad(user.id(), this::loadFromDatabase);
    }

    /** 将调用方的已授权作用域收窄为可选的用户选择。 */
    public AuthorizationScope resolve(CurrentUser user, Set<Long> requestedKbIds, Set<Long> requestedPageIds) {
        AuthorizationScope base = resolve(user);
        boolean constrained = (requestedKbIds != null && !requestedKbIds.isEmpty())
                || (requestedPageIds != null && !requestedPageIds.isEmpty());
        if (!constrained) return base;
        Set<Long> kbIds = new java.util.HashSet<>(requestedKbIds == null ? Set.of() : requestedKbIds);
        Set<Long> pageIds = new java.util.HashSet<>(requestedPageIds == null ? Set.of() : requestedPageIds);
        if (!base.superuser()) {
            kbIds.retainAll(base.accessibleKbIds());
            pageIds.retainAll(base.accessiblePageIds());
        }
        if (jdbc != null && !kbIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(kbIds.size(), "?"));
            Set<Long> pagesInSelectedKbs = new java.util.HashSet<>(jdbc.query(
                    "SELECT id FROM wiki_page WHERE status = 'ACTIVE' AND kb_id IN (" + placeholders + ")",
                    (rs, row) -> rs.getLong(1), kbIds.toArray()));
            if (!base.superuser()) pagesInSelectedKbs.retainAll(base.accessiblePageIds());
            pageIds.addAll(pagesInSelectedKbs);
        }
        if (jdbc != null && !pageIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(pageIds.size(), "?"));
            List<Long> activePages = jdbc.query("SELECT id FROM wiki_page WHERE status = 'ACTIVE' AND id IN (" + placeholders + ")",
                    (rs, row) -> rs.getLong(1), pageIds.toArray());
            pageIds.retainAll(activePages);
        }
        Map<Long, Long> versions = new HashMap<>();
        kbIds.forEach(id -> versions.put(id, scopeVersions.current(id)));
        return new AuthorizationScope(user.id(), false, kbIds, versions, pageIds);
    }

    private AuthorizationScope loadFromDatabase(long userId) {
        List<KnowledgeBaseMember> memberships = members.findByUserId(userId);
        Set<Long> kbIds = memberships.stream()
                .map(KnowledgeBaseMember::getKbId)
                .collect(Collectors.toSet());
        // 归档的知识库永不扩大检索作用域：成员关系记录会保留过归档，
        // 但权威状态不会。
        if (jdbc != null && !kbIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(kbIds.size(), "?"));
            Object[] args = new Object[kbIds.size()];
            int index = 0;
            for (Long kbId : kbIds) {
                args[index++] = kbId;
            }
            List<Long> active = jdbc.query(
                    "SELECT id FROM knowledge_base WHERE status = 'ACTIVE' AND id IN ("
                            + placeholders + ")",
                    (rs, row) -> rs.getLong(1), args);
            kbIds = new java.util.HashSet<>(active);
        }
        Map<Long, Long> versions = new HashMap<>();
        for (Long kbId : kbIds) {
            versions.put(kbId, scopeVersions.current(kbId));
        }
        Set<Long> pageIds = jdbc == null ? Set.of() : new java.util.HashSet<>(jdbc.query(
                "SELECT DISTINCT p.id FROM wiki_page p "
                        + "LEFT JOIN wiki_page_member pm ON pm.page_id = p.id AND pm.user_id = ? "
                        + "LEFT JOIN wiki_page_audience_member pa ON pa.page_id = p.id AND pa.user_id = ? "
                        + "WHERE p.status = 'ACTIVE' AND (p.owner_id = ? OR pm.user_id IS NOT NULL "
                        + "OR (p.audience_mode = 'KB_MEMBERS' AND EXISTS (SELECT 1 FROM knowledge_base_member km "
                        + "WHERE km.kb_id = p.kb_id AND km.user_id = ?)) "
                        + "OR (p.audience_mode = 'SELECTED_MEMBERS' AND EXISTS (SELECT 1 FROM knowledge_base_member sm "
                        + "WHERE sm.kb_id = pa.source_kb_id AND sm.user_id = ?)))",
                (rs, row) -> rs.getLong(1), userId, userId, userId, userId, userId));
        return new AuthorizationScope(userId, false, kbIds, versions, pageIds);
    }

    public void invalidate(long userId) {
        scopeCache.invalidate(userId);
    }
}
