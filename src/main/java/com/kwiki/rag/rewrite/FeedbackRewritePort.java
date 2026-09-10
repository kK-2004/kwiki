package com.kwiki.rag.rewrite;

import java.util.Optional;

/**
 * 用于失败反馈改写的 Query Rewrite Agent 端口。实现
 * 最多返回一个必须与原始查询、上一次
 * 查询及所有历史改写都不同的查询；空表示模型未产出任何
 * 可用内容（该调用仍消耗改写预算）。
 */
public interface FeedbackRewritePort {

    Optional<String> rewrite(FeedbackRewriteInput input);
}
