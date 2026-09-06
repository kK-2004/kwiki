package com.kwiki.wiki.access;

import java.util.Optional;

/**
 * Port for resolving a user's knowledge-base role. Implemented by the persistence
 * layer; kept as an interface so the authorization policy stays storage-free.
 */
public interface MembershipLookup {

    Optional<KnowledgeBaseRole> findRole(long kbId, long userId);
}
