package com.kwiki.eval;

import java.util.List;
import java.util.Map;

/**
 * 单题评测结果。score 仅对非负样本存在；refusedCorrectly 仅对负样本存在；
 * errorCode 非空表示该题执行失败，不计入召回分母。
 */
public record CaseResult(
        EvalCase evalCase,
        CaseScore score,
        Boolean refusedCorrectly,
        String outcome,
        String answer,
        List<String> rewrites,
        Map<EvalLayer, List<String>> chunkKeys,
        String errorCode,
        long elapsedMs) {

    public CaseResult {
        rewrites = rewrites == null ? List.of() : List.copyOf(rewrites);
        chunkKeys = chunkKeys == null ? Map.of() : Map.copyOf(chunkKeys);
        answer = answer == null ? "" : answer;
    }

    public boolean error() {
        return errorCode != null;
    }
}
