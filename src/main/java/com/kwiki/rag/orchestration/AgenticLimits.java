package com.kwiki.rag.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public record AgenticLimits(
        int rounds,
        int modelCalls,
        int toolCalls,
        int callsPerRound,
        int steps,
        Duration timeout,
        int bufferSize,
        int concurrentRuns) {
    public AgenticLimits(
            @Value("${kwiki.agentic.max-rounds:3}") int rounds,
            @Value("${kwiki.agentic.max-model-calls:16}") int modelCalls,
            @Value("${kwiki.agentic.max-tool-calls:9}") int toolCalls,
            @Value("${kwiki.agentic.calls-per-round:3}") int callsPerRound,
            @Value("${kwiki.agentic.max-steps:64}") int steps,
            @Value("${kwiki.agentic.timeout:180s}") Duration timeout,
            @Value("${kwiki.agentic.buffer-size:256}") int bufferSize,
            @Value("${kwiki.agentic.concurrent-runs:16}") int concurrentRuns) {
        if (rounds < 1
                || rounds > 3
                || modelCalls < 1
                || modelCalls > 16
                || toolCalls < 1
                || toolCalls > 9
                || callsPerRound < 1
                || callsPerRound > 3
                || steps < 1
                || steps > 64
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || bufferSize < 1
                || bufferSize > 4096
                || concurrentRuns < 1
                || concurrentRuns > 128)
            throw new IllegalArgumentException("invalid agentic limits");
        this.rounds = rounds;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.callsPerRound = callsPerRound;
        this.steps = steps;
        this.timeout = timeout;
        this.bufferSize = bufferSize;
        this.concurrentRuns = concurrentRuns;
    }

    public static AgenticLimits defaults() {
        return new AgenticLimits(3, 16, 9, 3, 64, Duration.ofSeconds(180), 256, 16);
    }
}
