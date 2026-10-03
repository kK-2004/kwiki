package com.kwiki.graph.build;

/** 子任务阶段进度的持久化快照；由执行器带隔离令牌更新。 */
public record RunProgress(long sourcesTotal, long sourcesExtracted, long extractionCacheHits,
                          long communitiesTotal, long communitiesSummarized) {

    public static final RunProgress EMPTY =
            new RunProgress(0, 0, 0, 0, 0);
}
