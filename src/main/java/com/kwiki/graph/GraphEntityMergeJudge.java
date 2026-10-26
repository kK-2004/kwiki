package com.kwiki.graph;

import java.util.Optional;

/**
 * 有争议实体合并的判定：候选与已登记实体只是别名相同或同名不同类型时，判断是否指同一事物。
 * 返回 empty 表示无法判定（未启用、模型不可用或调用失败），调用方按「不合并」处理。
 */
public interface GraphEntityMergeJudge {

    /** 待判定的抽取候选：名称、类型、别名与所在块片段。 */
    record Candidate(String canonicalName, GraphEntityType entityType, java.util.List<String> aliases,
                     String contextSnippet) {
    }

    record Verdict(boolean sameEntity, String reason, String judge) {
    }

    Optional<Verdict> judge(Candidate candidate, GraphEntityRegistryPort.RegisteredEntity existing);

    static GraphEntityMergeJudge none() {
        return (candidate, existing) -> Optional.empty();
    }
}
