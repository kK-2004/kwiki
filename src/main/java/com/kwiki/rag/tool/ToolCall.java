package com.kwiki.rag.tool;

public record ToolCall(String callId, String name, String rawArguments, String modelTurnId) {}
