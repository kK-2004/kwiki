package com.kwiki.graph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 知识库内实体注册的持久化边界：按「归一化名称 + 类型」唯一登记，别名单独索引，
 * 有争议合并的判定结果缓存，避免重建或重试时重复判定。
 */
public interface GraphEntityRegistryPort {

    /** 已登记实体；contextSnippet 为首次登记时所在块的片段，供争议判定参考。 */
    record RegisteredEntity(String entityId, String canonicalName, GraphEntityType entityType,
                            List<String> aliases, String contextSnippet) {

        public GraphEntity toEntity() {
            return new GraphEntity(entityId, canonicalName, entityType, aliases);
        }
    }

    /** 归一化名称与类型都相同的已登记实体。 */
    Optional<RegisteredEntity> findByName(long kbId, String normalizedName, GraphEntityType type,
                                          String resolverVersion);

    /** 名称或别名命中任一归一化词的已登记实体（不限类型），最多 limit 个。 */
    List<RegisteredEntity> findByTerms(long kbId, Collection<String> normalizedTerms,
                                       String resolverVersion, int limit);

    /** 登记新实体；并发下同名同类型已被他人登记时返回已有的那个。 */
    RegisteredEntity register(long kbId, GraphEntity candidate, String normalizedName,
                              String contextSnippet, String resolverVersion);

    /** 给实体追加归一化别名（幂等）。 */
    void addAliases(long kbId, String entityId, Collection<String> normalizedAliases, String resolverVersion);

    Optional<Boolean> findMergeDecision(long kbId, String resolverVersion, String candidateKey, String entityId);

    void saveMergeDecision(long kbId, String resolverVersion, String candidateKey, String entityId,
                           boolean sameEntity, String judge, String reason);
}
