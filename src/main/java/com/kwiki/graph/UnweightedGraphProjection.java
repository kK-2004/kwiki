package com.kwiki.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** 供原生无权 Leiden 使用的实体-only 投影；不携带关系权重。 */
public record UnweightedGraphProjection(
        long kbId,
        long graphVersion,
        List<String> entityIds,
        List<Edge> edges,
        String projectionHash) {

    public record Edge(String leftEntityId, String rightEntityId) {
        public Edge {
            if (leftEntityId == null || rightEntityId == null
                    || leftEntityId.isBlank() || rightEntityId.isBlank()
                    || leftEntityId.compareTo(rightEntityId) >= 0) {
                throw new IllegalArgumentException("无权投影边必须是有序的不同端点");
            }
        }
    }

    public UnweightedGraphProjection {
        if (kbId <= 0 || graphVersion <= 0) {
            throw new IllegalArgumentException("投影版本身份无效");
        }
        entityIds = entityIds == null ? List.of() : entityIds.stream().distinct().sorted().toList();
        edges = edges == null ? List.of() : List.copyOf(edges);
        projectionHash = require(projectionHash);
    }

    public static String hash(long kbId, long graphVersion,
                              List<String> entityIds, List<Edge> edges) {
        String canonical = kbId + "|" + graphVersion + "|"
                + String.join(",", entityIds) + "|"
                + edges.stream().map(edge -> edge.leftEntityId() + ":" + edge.rightEntityId())
                        .sorted().reduce((a, b) -> a + "," + b).orElse("");
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行时缺少 SHA-256", failure);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("投影 hash 不能为空");
        return value;
    }
}
