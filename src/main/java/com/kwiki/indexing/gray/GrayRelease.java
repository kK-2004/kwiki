package com.kwiki.indexing.gray;

import java.time.Instant;
import java.util.List;

/** 灰度发布的只读视图，含知识库名称，供服务与接口使用。 */
public record GrayRelease(long id, String name, String parserVersion, int indexVersionNumber,
                          GrayReleaseStatus status, String lastError, String createdBy,
                          Instant createdAt, Instant switchedAt, Instant endedAt, List<Kb> kbs) {

    public record Kb(long kbId, String name) { }

    public List<Long> kbIds() {
        return kbs.stream().map(Kb::kbId).toList();
    }
}
