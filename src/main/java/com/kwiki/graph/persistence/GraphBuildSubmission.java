package com.kwiki.graph.persistence;

import java.util.List;

/** 图批次提交结果；重复幂等键只返回既有批次身份。 */
public record GraphBuildSubmission(long batchId, long communityIndexVersion,
                                   List<Long> runIds, boolean replayed) {
    public GraphBuildSubmission {
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
    }
}
