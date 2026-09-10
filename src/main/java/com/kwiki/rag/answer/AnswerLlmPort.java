package com.kwiki.rag.answer;

import reactor.core.publisher.Flux;

/**
 * 流式答案提供方端口：增量推送答案文本；订阅
 * 取消必须传播到提供方流。
 */
public interface AnswerLlmPort {

    /** 为给定 prompt 流式输出回答增量；错误在上游被净化。 */
    Flux<String> streamAnswer(String prompt);
}
