package com.kwiki.wiki.persistence;

import com.kwiki.wiki.access.KnowledgeBaseRole;
import com.kwiki.wiki.access.MembershipLookup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MembershipLookup adapter backed by the knowledge_base_member table. When JPA
 * repositories are not available (connection-free test contexts) it fails closed
 * by resolving no roles.
 */
@Repository
public class JpaMembershipLookup implements MembershipLookup {

    private final KnowledgeBaseMemberRepository members;

    public JpaMembershipLookup(ObjectProvider<KnowledgeBaseMemberRepository> members) {
        this.members = members.getIfAvailable();
    }

    @Override
    public Optional<KnowledgeBaseRole> findRole(long kbId, long userId) {
        if (members == null) {
            return Optional.empty();
        }
        return members.findByKbIdAndUserId(kbId, userId)
                .map(com.kwiki.wiki.domain.KnowledgeBaseMember::getRole);
    }
}
