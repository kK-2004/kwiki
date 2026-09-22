package com.kwiki.graph.persistence;

/** 快照 READY 的硬门禁；任何一项未完成都不能发布。 */
public record GraphValidationChecklist(
        boolean sourceCoverage,
        boolean membershipComplete,
        boolean entityMappingConsistent,
        boolean summariesComplete,
        boolean embeddingsComplete,
        boolean mappingCompatible,
        boolean refreshReadable) {

    public boolean valid() {
        return sourceCoverage && membershipComplete && entityMappingConsistent
                && summariesComplete && embeddingsComplete && mappingCompatible
                && refreshReadable;
    }

    public void requireValid() {
        if (!valid()) throw new IllegalStateException("图快照校验未通过，不能进入 READY");
    }
}
