package com.kwiki.wiki.access;

import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
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

    public AuthorizationScopeResolver(KnowledgeBaseMemberRepository members,
                                      ScopeVersionService scopeVersions,
                                      ScopeCache scopeCache) {
        this.members = members;
        this.scopeVersions = scopeVersions;
        this.scopeCache = scopeCache;
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
        return new AuthorizationScope(userId, false, kbIds, versions);
    }

    public void invalidate(long userId) {
        scopeCache.invalidate(userId);
    }
}
