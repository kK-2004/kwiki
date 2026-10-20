package com.kwiki.eval;

/** 评测分层：从单路候选池到最终回答。 */
public enum EvalLayer {
    BM25_50("bm25@50"),
    VECTOR_50("vector@50"),
    POOL_50("pool@50"),
    FUSED_8("fused@8"),
    FUSED_20("fused@20"),
    CONTEXT("context"),
    ANSWER("answer");

    private final String metric;

    EvalLayer(String metric) {
        this.metric = metric;
    }

    public String metric() {
        return metric;
    }
}
