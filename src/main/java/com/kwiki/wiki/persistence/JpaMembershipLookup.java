package com.kwiki.wiki.persistence;

import com.kwiki.wiki.access.KnowledgeBaseRole;
import com.kwiki.wiki.access.MembershipLookup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 由 knowledge_base_member 表支撑的 MembershipLookup 适配器。当 JPA
 * 仓储不可用时（无连接的测试环境），它会默认拒绝，即解析不出任何角色。
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
