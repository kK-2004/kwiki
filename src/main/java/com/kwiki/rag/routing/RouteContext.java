package com.kwiki.rag.routing;

import java.util.List;

/**
 * 路由的额外输入：上一轮路由状态、近期用户消息（最旧在前，仅作数据）、
 * 以及本次 run 是否是对反问的续跑（续跑时禁止再反问）。
 */
public record RouteContext(PriorRoute prior, List<String> recentUserQueries, boolean clarificationResumed) {

    public static final RouteContext EMPTY = new RouteContext(null, List.of(), false);

    public RouteContext {
        recentUserQueries = recentUserQueries == null ? List.of() : List.copyOf(recentUserQueries);
    }
}
