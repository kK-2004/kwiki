package com.kwiki.rag.retrieval;

import java.util.List;

/**
 * 一个被检索到的 CHILD 分块，并带有生成引用所需的溯源信息。
 * contentIds 是由块内受保护块重建的去重图片资源身份
 * （旧索引不含该字段时为空列表）；URL 绝不持久化——回显时
 * 经授权后按 contentId 即时签发。
 */
public record ChunkHit(
        String chunkKey,
        String parentChunkKey,
        long kbId,
        String resourceType,
        long resourceId,
        Long revisionId,
        String headingPath,
        int charStart,
        int charEnd,
        String content,
        List<Long> contentIds,
        String sourceChunkId,
        List<String> entityIds,
        String entityLinkingVersion,
        EntityLinkingStatus entityLinkingStatus) {

    public ChunkHit {
        // 资源身份按首次出现去重——结构化字段是回显的装配依据
        contentIds = contentIds == null
                ? List.of()
                : contentIds.stream().distinct().toList();
        entityIds = entityIds == null
                ? List.of()
                : entityIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
        entityLinkingStatus = entityLinkingStatus == null
                ? EntityLinkingStatus.MISSING : entityLinkingStatus;
    }

    /** 兼容已有调用方；旧索引没有实体字段时按 MISSING 处理。 */
    public ChunkHit(
            String chunkKey,
            String parentChunkKey,
            long kbId,
            String resourceType,
            long resourceId,
            Long revisionId,
            String headingPath,
            int charStart,
            int charEnd,
            String content,
            List<Long> contentIds) {
        this(chunkKey, parentChunkKey, kbId, resourceType, resourceId, revisionId,
                headingPath, charStart, charEnd, content, contentIds,
                null, List.of(), null, EntityLinkingStatus.MISSING);
    }

    public ChunkHit(
            String chunkKey,
            String parentChunkKey,
            long kbId,
            String resourceType,
            long resourceId,
            Long revisionId,
            String headingPath,
            int charStart,
            int charEnd,
            String content) {
        this(chunkKey, parentChunkKey, kbId, resourceType, resourceId, revisionId,
                headingPath, charStart, charEnd, content, List.of());
    }

}
