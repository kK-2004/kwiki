package com.kwiki.graph;

import java.util.Map;

/**
 * 图增强 run 的出站守卫补充：在入模、遍历、引用与 SSE 输出前复核固定快照
 * 的 content/security epoch 与当前值。任何失效都必须中止输出并要求重新
 * 检索，不得作为普通服务故障降级后继续输出缓存图或摘要。
 */
public final class GraphRunOutboundGuard {

    public enum Verdict {
        OK,
        /** 快照 epoch 已过期：中止输出，禁止以降级方式继续。 */
        SNAPSHOT_EPOCH_INVALID
    }

    private GraphRunOutboundGuard() {
    }

    public static Verdict check(GraphSnapshot snapshot, long currentContentEpoch,
                                long currentSecurityEpoch) {
        if (snapshot != null && snapshot.contentEpoch() == currentContentEpoch
                && snapshot.securityEpoch() == currentSecurityEpoch) {
            return Verdict.OK;
        }
        return Verdict.SNAPSHOT_EPOCH_INVALID;
    }

    /** 请求固定的全部快照逐一复核；任一失效即整体失效。 */
    public static Verdict checkAll(Map<Long, GraphSnapshot> pinned,
                                   Map<Long, long[]> currentEpochs) {
        if (pinned == null || pinned.isEmpty()) {
            return Verdict.OK;
        }
        for (Map.Entry<Long, GraphSnapshot> entry : pinned.entrySet()) {
            long[] current = currentEpochs == null ? null : currentEpochs.get(entry.getKey());
            if (current == null || check(entry.getValue(), current[0], current[1]) != Verdict.OK) {
                return Verdict.SNAPSHOT_EPOCH_INVALID;
            }
        }
        return Verdict.OK;
    }
}
