package com.kwiki.graph.plugin;

import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.CommunitySummaryInput;
import com.kwiki.indexing.plugin.IndexPlugin;

/** 图构建社区摘要环节插件：为单个社区生成有来源约束的中文摘要。 */
public interface CommunitySummarizerPlugin extends IndexPlugin {

    /** 摘要提示词版本，写入快照与社区索引。 */
    String promptVersion();

    /** 返回的模型名写入快照与社区索引（summaryModel）。 */
    String modelName();

    CommunitySummary summarize(CommunitySummaryInput input);
}
