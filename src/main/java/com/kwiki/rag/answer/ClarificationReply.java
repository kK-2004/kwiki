package com.kwiki.rag.answer;

/** 请求体里对一次反问的回答：选了某个选项（optionId），或自由输入（text）。 */
public record ClarificationReply(String id, String optionId, String text) {
}
