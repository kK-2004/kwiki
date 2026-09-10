package com.kwiki.rag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 带版本号的 SSE 事件：route、rewrite、retrieve、token、citations、done 或 error。每一帧
 * 都携带稳定的 request id 与单调递增序号；一个终止事件（done 或 error）
 * 结束该流。
 */
public record ChatStreamEvent(String type, long sequence, String requestId, String payloadJson) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ChatStreamEvent of(String type, long sequence, String requestId, Object payload) {
        try {
            return new ChatStreamEvent(
                    type, sequence, requestId, MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalArgumentException("sse payload serialization failed");
        }
    }

    /** 线上格式：{"seq":n,"requestId":"...","type":"...",<payload fields>} */
    public String toWire() {
        return "event: " + type + "\ndata: " + toJson() + "\n\n";
    }

    public String toJson() {
        try {
            ObjectNode wire = MAPPER.createObjectNode();
            wire.put("seq", sequence);
            wire.put("requestId", requestId);
            wire.put("type", type);
            JsonNode payload = MAPPER.readTree(payloadJson);
            if (payload.isObject()) {
                payload.properties()
                        .forEach(
                                entry -> {
                                    if (!java.util.Set.of("seq", "requestId", "type")
                                            .contains(entry.getKey()))
                                        wire.set(entry.getKey(), entry.getValue());
                                });
            }
            return MAPPER.writeValueAsString(wire);
        } catch (Exception e) {
            throw new IllegalArgumentException("sse payload serialization failed");
        }
    }

    /** 供测试与适配器使用的 Map 负载辅助方法。 */
    public Map<String, Object> payloadMap() {
        try {
            return MAPPER.convertValue(MAPPER.readTree(payloadJson), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
