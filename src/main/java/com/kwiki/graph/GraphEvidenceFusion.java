package com.kwiki.graph;

import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.StandardRrfFusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图关系来源与原 Chunk 融合列表的合并。图列表按稳定顺序排列（种子召回次序、
 * 较短 hop、可见来源支持数降序、稳定来源 ID），与原 Chunk 融合列表用标准
 * RRF k=60 去重合并后重新应用 finalTopK；最多新增 5 个 CHILD，关系说明等
 * 辅助文本合计不超过 4,000 字符并计入既有阶段总预算。没有保留原文支撑的
 * 断言与引用一律移除。
 */
public final class GraphEvidenceFusion {

    public static final int MAX_NEW_CHILDREN = 5;
    public static final int MAX_AUX_TEXT_CHARS = 4_000;
    public static final int RRF_K = 60;

    /** 已解析到当前有效 CHILD 的图关系证据。 */
    public record GraphRelationEvidence(
            long kbId,
            String relationId,
            int seedOrder,
            int hops,
            int visibleSupportCount,
            List<String> sourceChunkIds,
            List<ChildEvidence> resolvedChildren,
            String assertion) {
        public GraphRelationEvidence {
            if (relationId == null || relationId.isBlank() || hops < 1
                    || seedOrder < 1 || visibleSupportCount < 0) {
                throw new IllegalArgumentException("图关系证据无效");
            }
            sourceChunkIds = sourceChunkIds == null ? List.of()
                    : List.copyOf(sourceChunkIds);
            resolvedChildren = resolvedChildren == null ? List.of()
                    : List.copyOf(resolvedChildren);
            assertion = assertion == null ? "" : assertion;
        }

        public GraphRelationEvidence withResolvedChildren(List<ChildEvidence> children) {
            return new GraphRelationEvidence(kbId, relationId, seedOrder, hops,
                    visibleSupportCount, sourceChunkIds, children, assertion);
        }
    }

    public record FusionOutcome(
            List<ChildEvidence> mergedChildren,
            List<ChildEvidence> newChildren,
            List<String> retainedRelationIds,
            int auxTextChars,
            List<String> droppedReasons) {
    }

    private GraphEvidenceFusion() {
    }

    public static FusionOutcome fuse(List<ChildEvidence> chunkList,
                                     List<GraphRelationEvidence> graphRelations,
                                     int finalTopK) {
        return fuse(chunkList, graphRelations, finalTopK, MAX_NEW_CHILDREN);
    }

    /**
     * @param maxNewChildren 本次调用允许新增的 CHILD 上限（run 级剩余额度），
     *                       与静态上限取较小值
     */
    public static FusionOutcome fuse(List<ChildEvidence> chunkList,
                                     List<GraphRelationEvidence> graphRelations,
                                     int finalTopK, int maxNewChildren) {
        List<ChildEvidence> chunkFused = chunkList == null ? List.of() : chunkList;
        List<GraphRelationEvidence> ordered = orderGraphRelations(graphRelations);
        int newChildrenCap = Math.min(Math.max(0, maxNewChildren), MAX_NEW_CHILDREN);

        Set<String> existingKeys = new LinkedHashSet<>();
        for (ChildEvidence child : chunkFused) {
            existingKeys.add(child.chunkKey());
        }

        // 预算内选取新增 CHILD：去重、不与原列表重复、最多 5 个、
        // 辅助文本（新增 CHILD 正文 + 关系说明）合计 ≤ 4,000 字符。
        List<ChildEvidence> newChildren = new ArrayList<>();
        Set<String> newKeys = new LinkedHashSet<>();
        int auxTextChars = 0;
        List<String> droppedReasons = new ArrayList<>();
        for (GraphRelationEvidence relation : ordered) {
            for (ChildEvidence child : relation.resolvedChildren()) {
                if (newChildren.size() >= newChildrenCap) {
                    droppedReasons.add("graph-new-children-capped");
                    break;
                }
                if (existingKeys.contains(child.chunkKey())
                        || newKeys.contains(child.chunkKey())) {
                    continue;
                }
                int assertionShare = relation.assertion().length();
                if (auxTextChars + child.content().length() + assertionShare
                        > MAX_AUX_TEXT_CHARS) {
                    droppedReasons.add("graph-aux-text-budget");
                    break;
                }
                auxTextChars += child.content().length() + assertionShare;
                newChildren.add(child);
                newKeys.add(child.chunkKey());
            }
        }

        // 只保留至少一个来源 CHILD 仍留在结果中的关系断言。
        Set<String> retainedKeys = new HashSet<>(newKeys);
        List<String> retainedRelationIds = new ArrayList<>();
        for (GraphRelationEvidence relation : ordered) {
            boolean supported = relation.resolvedChildren().stream()
                    .anyMatch(child -> retainedKeys.contains(child.chunkKey())
                            || existingKeys.contains(child.chunkKey()));
            if (supported) {
                retainedRelationIds.add(relation.relationId());
            } else {
                droppedReasons.add("relation-without-original-text:"
                        + relation.relationId());
            }
        }

        // RRF k=60 去重合并：原 Chunk 融合列表保留其内部排名，图列表按图内次序。
        List<StandardRrfFusion.RankedList> rankings = new ArrayList<>();
        List<String> chunkKeys = new ArrayList<>();
        Map<String, ChildEvidence> byKey = new LinkedHashMap<>();
        for (ChildEvidence child : chunkFused) {
            if (!byKey.containsKey(child.chunkKey())) {
                byKey.put(child.chunkKey(), child);
                chunkKeys.add(child.chunkKey());
            }
        }
        rankings.add(new StandardRrfFusion.RankedList("CHUNK", chunkKeys));
        List<String> graphKeys = new ArrayList<>();
        for (ChildEvidence child : newChildren) {
            if (!byKey.containsKey(child.chunkKey())) {
                byKey.put(child.chunkKey(), child);
                graphKeys.add(child.chunkKey());
            }
        }
        rankings.add(new StandardRrfFusion.RankedList("GRAPH", graphKeys));

        int topK = Math.max(1, finalTopK);
        List<ChildEvidence> merged = new ArrayList<>();
        for (StandardRrfFusion.FusedChunk candidate : StandardRrfFusion.fuse(rankings, RRF_K)) {
            if (merged.size() >= topK) {
                break;
            }
            ChildEvidence child = byKey.get(candidate.chunkKey());
            if (child == null) {
                continue;
            }
            merged.add(child.withRrfScore(candidate.score()));
        }
        return new FusionOutcome(List.copyOf(merged), List.copyOf(newChildren),
                List.copyOf(retainedRelationIds), auxTextChars, List.copyOf(droppedReasons));
    }

    /** 图列表稳定顺序：种子召回次序、较短 hop、可见来源支持数降序、稳定来源 ID。 */
    private static List<GraphRelationEvidence> orderGraphRelations(
            List<GraphRelationEvidence> relations) {
        List<GraphRelationEvidence> ordered = new ArrayList<>(
                relations == null ? List.of() : relations);
        ordered.sort(Comparator
                .comparingInt(GraphRelationEvidence::seedOrder)
                .thenComparingInt(GraphRelationEvidence::hops)
                .thenComparing(Comparator.comparingInt(
                        GraphRelationEvidence::visibleSupportCount).reversed())
                .thenComparing(relation -> relation.sourceChunkIds().isEmpty()
                        ? "" : relation.sourceChunkIds().get(0))
                .thenComparing(GraphRelationEvidence::relationId));
        return ordered;
    }
}
