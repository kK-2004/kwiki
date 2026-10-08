package com.kwiki.rag.orchestration;

import com.kwiki.rag.routing.PriorRoute;

/** AgenticAnswerService 传给工作流的路由输入：上一轮路由、本次是否为反问续跑。 */
public record RouteInput(PriorRoute prior, boolean clarificationResumed) {

    public static final RouteInput NONE = new RouteInput(null, false);
}
