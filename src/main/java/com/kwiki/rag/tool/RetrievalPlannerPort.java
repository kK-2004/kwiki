package com.kwiki.rag.tool;

import java.util.List;

public interface RetrievalPlannerPort {
    List<ToolCall> plan(
            String original,
            List<String> queries,
            List<String> gaps,
            List<ToolExchange> history,
            String validationError);

    record ToolExchange(ToolCall call, ToolResult result) {}
}
