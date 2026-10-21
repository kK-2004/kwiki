package com.kwiki.eval;

/** 评测 run 状态；CANCELLED 包括手动中断与实例重启/失联导致的中断。 */
public enum EvalRunState {
    RUNNING, COMPLETED, REPORT_PARTIAL, CANCELLED, FAILED
}
