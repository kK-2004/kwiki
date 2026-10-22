package com.kwiki.eval;

/**
 * 评测 run 状态。QUEUED 为已提交待认领；RUNNING 被某实例执行中；
 * CANCELLED 包括手动中断（含排队中取消）与实例重启/失联导致的中断。
 */
public enum EvalRunState {
    QUEUED, RUNNING, COMPLETED, REPORT_PARTIAL, CANCELLED, FAILED
}
