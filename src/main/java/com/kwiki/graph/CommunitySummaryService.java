package com.kwiki.graph;

import java.util.Optional;

/** 社区摘要编排边界；空社区不调用模型，非空结果必须通过来源校验。 */
public final class CommunitySummaryService {

    private final CommunitySummaryPort port;

    public CommunitySummaryService(CommunitySummaryPort port) {
        this.port = port;
    }

    public Optional<CommunitySummary> summarize(CommunitySummaryInput input) {
        if (input.representativeEntities().isEmpty()
                && input.representativeRelations().isEmpty()) {
            return Optional.empty();
        }
        CommunitySummary summary = port.summarize(input);
        CommunitySummaryValidator.requireValid(input, summary);
        return Optional.of(summary);
    }
}
