package com.kwiki.rag.answer;

import com.kwiki.rag.rewrite.ChatTurn;

import java.util.List;

/**
 * 无需检索时的直答端口。模型认为问题其实需要知识库内容时，整段只输出
 * {@link #ESCALATE_SENTINEL}，调用方据此转入检索。
 */
public interface DirectAnswerPort {

    String ESCALATE_SENTINEL = "[[NEEDS_KNOWLEDGE]]";

    /** 返回完整回答文本（服务端收集后再发布）；失败时抛异常，调用方转入检索。 */
    String answer(String question, List<ChatTurn> history);
}
