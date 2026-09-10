package com.kwiki.rag.orchestration;

/**
 * QA 门禁回答链路的终止/结果错误码。内容耗尽
 * 类结果区别于基础设施故障：前者以
 * 固定的信息不足文案与空引用作答，后者暴露
 * 具体的故障类别，而不会假装知识库是空的。
 */
public final class AgenticErrorCodes {

    /** 内容恢复已耗尽：以固定的信息不足文案作答。 */
    public static final String INSUFFICIENT = "insufficient";

    // 基础设施 / 终止性故障
    public static final String INVALID_QUERY = "invalid-query";
    public static final String AGENT_BUSY = "agent-busy";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";
    public static final String INTERNAL_ERROR = "internal-error";
    public static final String RETRIEVAL_FAILED = "retrieval-failed";
    public static final String AUTHORIZATION_CHANGED = "authorization-changed";
    public static final String QA_UNAVAILABLE = "qa-unavailable";
    public static final String REWRITE_UNAVAILABLE = "rewrite-unavailable";
    public static final String ANSWER_PROVIDER_FAILED = "answer-provider-failed";
    public static final String ANSWER_TOO_LONG = "answer-too-long";
    public static final String ANSWER_VALIDATION_FAILED = "answer-validation-failed";
    public static final String BACKPRESSURE_OVERFLOW = "backpressure-overflow";

    /** 所有内容恢复路径都耗尽后的精确拒绝文案。 */
    public static final String INSUFFICIENT_MESSAGE = "知识库中没有相关信息，我无法进行回答";

    private AgenticErrorCodes() {
    }
}
