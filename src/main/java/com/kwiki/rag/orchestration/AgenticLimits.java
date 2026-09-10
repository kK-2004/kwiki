package com.kwiki.rag.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 单个智能体化运行在 QA 门控状态机下的预算上限。该
 * 默认值覆盖最差的有效知识路径：1 次路由调用 + 2 次查询改写
 * 调用 + 最多 12 次候选生成 + 12 次 QA 评审 = 32 次对话内
 * 模型调用；12 次工具调用内含 6 次混合检索与 6 次父级拉取；
 * 128 个步骤；以及所有环节共享的单个 300s 截止时间。这些
 * 上限是上界而非目标——截止时间可提前结束运行。
 */
@Component
public record AgenticLimits(
        int queryRounds,
        int rewrites,
        int modelCalls,
        int toolCalls,
        int callsPerRound,
        int steps,
        Duration timeout,
        int bufferSize,
        int concurrentRuns,
        int hybridRetrievals,
        int parentFetches,
        int generations,
        int qualityReviews,
        Duration modelDeadline) {

    public AgenticLimits(
            @Value("${kwiki.agentic.max-query-rounds:3}") int queryRounds,
            @Value("${kwiki.agentic.max-rewrites:2}") int rewrites,
            @Value("${kwiki.agentic.max-model-calls:32}") int modelCalls,
            @Value("${kwiki.agentic.max-tool-calls:12}") int toolCalls,
            @Value("${kwiki.agentic.calls-per-round:3}") int callsPerRound,
            @Value("${kwiki.agentic.max-steps:128}") int steps,
            @Value("${kwiki.agentic.timeout:300s}") Duration timeout,
            @Value("${kwiki.agentic.buffer-size:256}") int bufferSize,
            @Value("${kwiki.agentic.concurrent-runs:16}") int concurrentRuns,
            @Value("${kwiki.agentic.max-hybrid-retrievals:6}") int hybridRetrievals,
            @Value("${kwiki.agentic.max-parent-fetches:6}") int parentFetches,
            @Value("${kwiki.agentic.max-generations:12}") int generations,
            @Value("${kwiki.agentic.max-quality-reviews:12}") int qualityReviews,
            @Value("${kwiki.agentic.model-deadline:30s}") Duration modelDeadline) {
        if (queryRounds < 1
                || queryRounds > 3
                || rewrites < 0
                || rewrites > 2
                || modelCalls < 1
                || modelCalls > 32
                || toolCalls < 1
                || toolCalls > 12
                || callsPerRound < 1
                || callsPerRound > 3
                || steps < 1
                || steps > 128
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || bufferSize < 1
                || bufferSize > 4096
                || concurrentRuns < 1
                || concurrentRuns > 128
                || hybridRetrievals < 1
                || hybridRetrievals > 6
                || parentFetches < 1
                || parentFetches > 6
                || generations < 1
                || generations > 12
                || qualityReviews < 1
                || qualityReviews > 12
                || modelDeadline == null
                || modelDeadline.isNegative()
                || modelDeadline.isZero())
            throw new IllegalArgumentException("invalid agentic limits");
        this.queryRounds = queryRounds;
        this.rewrites = rewrites;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.callsPerRound = callsPerRound;
        this.steps = steps;
        this.timeout = timeout;
        this.bufferSize = bufferSize;
        this.concurrentRuns = concurrentRuns;
        this.hybridRetrievals = hybridRetrievals;
        this.parentFetches = parentFetches;
        this.generations = generations;
        this.qualityReviews = qualityReviews;
        this.modelDeadline = modelDeadline;
    }

    /** 旧版访问器：在新语义中，一个「round」即一个查询轮次。 */
    public int rounds() {
        return queryRounds;
    }

    public static AgenticLimits defaults() {
        return new AgenticLimits(3, 2, 32, 12, 3, 128, Duration.ofSeconds(300), 256, 16,
                6, 6, 12, 12, Duration.ofSeconds(30));
    }
}
