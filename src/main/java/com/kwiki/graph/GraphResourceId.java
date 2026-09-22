package com.kwiki.graph;

import java.util.Objects;

/** 资源级来源身份；权限证明的最小单元，不携带正文或标题。 */
public record GraphResourceId(String resourceType, long resourceId) {
    public GraphResourceId {
        if (resourceType == null || resourceType.isBlank() || resourceId <= 0) {
            throw new IllegalArgumentException("资源身份无效");
        }
        resourceType = Objects.requireNonNull(resourceType).trim().toUpperCase();
    }

    public static GraphResourceId page(long pageId) {
        return new GraphResourceId("PAGE", pageId);
    }

    public static GraphResourceId attachment(long attachmentId) {
        return new GraphResourceId("ATTACHMENT", attachmentId);
    }
}
