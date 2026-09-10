package com.kwiki.rag.rewrite;

import java.util.Optional;

/** 改写类 LLM 调用的端口；适配器负责强制截止时间与净化。 */
public interface RewriteLlmPort {

    /** 给定指令的补全内容；失败或超时时为空。 */
    Optional<String> complete(String systemInstruction, String userPrompt);
}
