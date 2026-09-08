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
 * Resolves the immutable per-request authorization scope: member knowledge bases
 * plus their current scope versions (admins are superusers). Goes through the Redis
 * scope cache; every cache path degrades to direct resolution without widening.
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

    private AuthorizationScope loadFromDatabase(long userId) {
        List<KnowledgeBaseMember> memberships = members.findByUserId(userId);
        Map<Long, Long> versions = new HashMap<>();
        Set<Long> kbIds = memberships.stream()
                .map(KnowledgeBaseMember::getKbId)
                .collect(Collectors.toSet());
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
