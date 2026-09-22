package com.kwiki.graph.persistence;

import java.time.LocalDate;

/** 当日调度触发记录；状态决定该日是否还需要补跑。 */
public record GraphScheduleTriggerRecord(LocalDate scheduleDate, String status,
                                         Long linkedBatchId) {
    public GraphScheduleTriggerRecord {
        if (scheduleDate == null || status == null || status.isBlank()) {
            throw new IllegalArgumentException("调度触发记录无效");
        }
    }

    public boolean consumed() {
        return true;
    }
}
