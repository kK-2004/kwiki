package com.kwiki.graph;

import java.util.List;

/** 与存储实现无关的实体候选及注册身份。 */
public record GraphEntity(
        String entityId,
        String canonicalName,
        GraphEntityType entityType,
        List<String> aliases) {

    public GraphEntity {
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalArgumentException("实体规范名称不能为空");
        }
        if (entityType == null) {
            throw new IllegalArgumentException("实体类型不能为空");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public GraphEntity withEntityId(String resolvedEntityId) {
        return new GraphEntity(resolvedEntityId, canonicalName, entityType, aliases);
    }
}
