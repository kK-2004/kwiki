package com.kwiki.graph;

/** 社区摘要生成端口。 */
public interface CommunitySummaryPort {

    CommunitySummary summarize(CommunitySummaryInput input);
}
