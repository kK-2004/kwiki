package com.kwiki.graph.build;

/** 图构建阶段失败的分类：可重试错误按退避重试后耗尽才置 FAILED；永久错误直接失败。 */
public final class GraphBuildFailure extends RuntimeException {

    /** 模型调用超时（可重试）；子任务据此统计超时次数，耗尽后作为失败码展示。 */
    public static final String MODEL_TIMEOUT = "MODEL_TIMEOUT";

    private final String code;
    private final boolean retryable;

    private GraphBuildFailure(String code, String summary, boolean retryable) {
        super(summary);
        this.code = code;
        this.retryable = retryable;
    }

    public static GraphBuildFailure retryable(String code, String summary) {
        return new GraphBuildFailure(code, summary, true);
    }

    public static GraphBuildFailure permanent(String code, String summary) {
        return new GraphBuildFailure(code, summary, false);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
