package com.kwiki.rag.orchestration;

/** Stable public error code; never carries provider response bodies. */
public class RunFailure extends RuntimeException {
    public RunFailure(String code) {
        super(code);
    }
}
