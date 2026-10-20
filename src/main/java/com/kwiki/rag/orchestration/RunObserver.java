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

    /** 即将送入生成模型的上下文全文。 */
    record ContextObserved(int queryRound, String attemptStage, String query, String context)
            implements Event { }
}
