package com.kwiki.rag.tool;

import java.util.List;
import java.util.Map;

/**
 * QA agent 的多工具 planner 端口：LLM 看到观察摘要与本轮可用工具，只产出 tool_calls（策略）；
 * 校验与执行由代码侧完成。空列表表示「证据已足够，无需调用工具」。
 */
public interface AgentPlannerPort {

    /**
     * @param observation     有界的观察摘要（证据按不可信数据处理，由调用方构造）
     * @param tools           本轮暴露的工具
     * @param maxCalls        本轮最多调用数
     * @param history         此前的调用与结果（按 modelTurnId 归并成助手轮次）
     * @param validationError 上一次输出被拒绝的原因，没有则为空串
     */
    record Request(
            Map<String, Object> observation,
            List<ToolRegistry.ToolDefinition> tools,
            int maxCalls,
            List<RetrievalPlannerPort.ToolExchange> history,
            String validationError) {
        public Request {
            observation = observation == null ? Map.of() : observation;
            tools = List.copyOf(tools);
            history = history == null ? List.of() : List.copyOf(history);
            validationError = validationError == null ? "" : validationError;
        }
    }

    List<ToolCall> plan(Request request);
}
