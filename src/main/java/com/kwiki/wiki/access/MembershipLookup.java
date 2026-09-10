package com.kwiki.wiki.access;

import java.util.Optional;

/**
 * 用于解析用户知识库角色的端口。由持久层实现；保留为接口以便授权策略不依赖存储。
 */
public interface MembershipLookup {

    Optional<KnowledgeBaseRole> findRole(long kbId, long userId);
}
