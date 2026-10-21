package com.kwiki.rag.orchestration;

import java.util.List;

/**
 * 运行观测扩展点：工作流在关键节点发出只读事件，供评测（以及将来的线上追踪 /
 * agent 评测）采集。观测者不得修改运行状态；未安装时为 {@link #NOOP}，
 * 事件 payload 不会被构造。
 */
@FunctionalInterface
public interface RunObserver {

    RunObserver NOOP = event -> { };

    void onEvent(Event event);

    /** 观测事件的标记接口。 */
    interface Event { }

    /** 反馈改写完成：新一轮的检索问题。 */
    record RewriteObserved(int queryRound, String newQuery) implements Event { }

    /** 子块召回完成（graphEnhanced=true 表示图增强合并后的结果）。 */
    record ChildrenObserved(int queryRound, String attemptStage, List<String> chunkKeys,
                            boolean graphEnhanced) implements Event {
        public ChildrenObserved {
            chunkKeys = List.copyOf(chunkKeys);
        }
    }

    /**
     * 即将送入生成模型的上下文：context 为全文（供展示），segments 为按证据拆分的片段
     * （子块阶段每个子块一段，父块阶段每个父块正文一段），带来源资源身份。
     */
    record ContextObserved(int queryRound, String attemptStage, String query, String context,
                           List<ContextSegment> segments) implements Event {
        public ContextObserved {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }

        public ContextObserved(int queryRound, String attemptStage, String query, String context) {
            this(queryRound, attemptStage, query, context, List.of());
        }
    }

    /** 上下文中的一段证据及其来源资源。 */
    record ContextSegment(String resourceType, long resourceId, String text) {
        public ContextSegment {
            text = text == null ? "" : text;
        }
    }
}
