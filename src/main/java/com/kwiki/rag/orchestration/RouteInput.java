package com.kwiki.rag.orchestration;

import com.kwiki.rag.routing.PriorRoute;

/** AgenticAnswerService 传给工作流的路由输入：上一轮路由、本次是否为反问续跑。 */
public record RouteInput(PriorRoute prior, boolean clarificationResumed) {

    public static final RouteInput NONE = new RouteInput(null, false);

    /**
     * 无人交互（如 RAG 评测）：没有人能回答澄清问题，因此路由不反问、规划器不提供 ask_user，
     * 也不改道直答——与「反问续跑」的约束相同。
     */
    public static final RouteInput NON_INTERACTIVE = new RouteInput(null, true);
}
