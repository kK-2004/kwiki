package com.kwiki.rag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Versioned SSE event: route, rewrite, retrieve, token, citations, done, or
 * error. Every frame carries the stable request id and a monotonic sequence
 * number; one terminal event (done or error) ends the stream.
 */
public record ChatStreamEvent(String type, long sequence, String requestId, String payloadJson) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ChatStreamEvent of(String type, long sequence, String requestId,
                                     Object payload) {
        try {
            return new ChatStreamEvent(type, sequence, requestId,
                    MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalArgumentException("sse payload serialization failed");
        }
    }

    /** Wire format: {"seq":n,"requestId":"...","type":"...",<payload fields>} */
    public String toWire() {
        try {
            ObjectNode wire = MAPPER.createObjectNode();
            wire.put("seq", sequence);
            wire.put("requestId", requestId);
            wire.put("type", type);
            JsonNode payload = MAPPER.readTree(payloadJson);
            if (payload.isObject()) {
                payload.properties().forEach(entry -> wire.set(entry.getKey(), entry.getValue()));
            }
            return "event: " + type + "\ndata: " + MAPPER.writeValueAsString(wire) + "\n\n";
        } catch (Exception e) {
            return "event: " + type + "\ndata: {\"seq\":" + sequence + ",\"requestId\":\""
                    + requestId + "\"}\n\n";
        }
    }

    /** Map payload helper for tests and adapters. */
    public Map<String, Object> payloadMap() {
        try {
            return MAPPER.convertValue(MAPPER.readTree(payloadJson), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
