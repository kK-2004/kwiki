package com.kwiki.rag.orchestration;

/**
 * Terminal/outcome error codes of the QA-gated answer path. Content-exhaustion
 * outcomes are distinct from infrastructure failures: the former answer with
 * the exact insufficient message and empty citations, the latter surface the
 * concrete failure class without pretending the knowledge base is empty.
 */
public final class AgenticErrorCodes {

    /** Content recovery exhausted: answer with the fixed insufficient message. */
    public static final String INSUFFICIENT = "insufficient";

    // infrastructure / terminal failures
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

    /** Exact refusal text once every content-recovery path is exhausted. */
    public static final String INSUFFICIENT_MESSAGE = "知识库中没有相关信息，我无法进行回答";

    private AgenticErrorCodes() {
    }
}
