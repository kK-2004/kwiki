package com.kwiki.rag.routing;

import java.util.Optional;

/** schema 受限的路由器 LLM 的端口；适配器必须遵守截止时间。 */
public interface RouterLlmPort {

    /** 原始的（已解析 JSON 但未校验的）LLM 决策；任何失败时为空。 */
    Optional<java.util.Map<String, Object>> askRouter(String normalizedQuery, String matchTrace);
}
