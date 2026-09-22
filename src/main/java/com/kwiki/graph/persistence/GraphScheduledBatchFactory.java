package com.kwiki.graph.persistence;

import java.time.LocalDate;
import java.util.Optional;

/** 由业务一致性读提供当天冻结的 ALL 范围与当前 Chunk 目标。 */
public interface GraphScheduledBatchFactory {
    Optional<GraphBuildBatchRequest> create(LocalDate scheduleDate);
}
