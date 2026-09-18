package com.kwiki.rag.orchestration;

/** 稳定的对外错误码；绝不携带服务提供方的响应体。 */
public class RunFailure extends RuntimeException {
    public RunFailure(String code) {
        super(code);
    }

    /** 保留仅供服务端诊断的根因；稳定错误码仍由 message 对外暴露。 */
    public RunFailure(String code, Throwable cause) {
        super(code, cause);
    }
}
