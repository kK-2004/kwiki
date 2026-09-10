package com.kwiki.rag.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Budget ceilings of one agentic run under the QA-gated state machine. The
 * defaults cover the worst valid knowledge path: 1 route call + 2 rewrite
 * calls + up to 12 candidate generations + 12 QA reviews = 27 conversational
 * model calls inside 32; 6 hybrid retrievals and 6 parent fetches inside 12
 * tool calls; 128 steps; a single 300s deadline shared by everything. The
 * ceilings are upper bounds, not targets — the deadline can end a run early.
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

    /** Legacy accessor: a "round" is one query round in the new semantics. */
    public int rounds() {
        return queryRounds;
    }

    public static AgenticLimits defaults() {
        return new AgenticLimits(3, 2, 32, 12, 3, 128, Duration.ofSeconds(300), 256, 16,
                6, 6, 12, 12, Duration.ofSeconds(30));
    }
}
