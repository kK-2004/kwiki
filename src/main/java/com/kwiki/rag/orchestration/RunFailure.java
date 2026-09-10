package com.kwiki.rag.orchestration;

/** 稳定的对外错误码；绝不携带服务提供方的响应体。 */
public class RunFailure extends RuntimeException {
    public RunFailure(String code) {
        super(code);
    }
}
