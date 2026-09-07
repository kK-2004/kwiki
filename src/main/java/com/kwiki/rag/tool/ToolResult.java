package com.kwiki.rag.tool;

import java.util.*;

public record ToolResult(
        String callId,
        String status,
        List<String> evidenceRefs,
        List<String> degradations,
        String errorCode,
        Map<String, Object> stats) {
    public ToolResult {
        evidenceRefs = List.copyOf(evidenceRefs);
        degradations = List.copyOf(degradations);
        stats = Map.copyOf(stats);
    }
}
