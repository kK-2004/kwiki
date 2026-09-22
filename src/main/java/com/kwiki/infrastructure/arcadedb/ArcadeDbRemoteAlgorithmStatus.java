package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.persistence.GraphRemoteAlgorithmState;

/** 将远端操作探测结果归一化；无法确认结束时必须保留算法槽位。 */
public final class ArcadeDbRemoteAlgorithmStatus {

    private ArcadeDbRemoteAlgorithmStatus() {
    }

    public static GraphRemoteAlgorithmState parse(String body, ObjectMapper mapper) {
        if (body == null || mapper == null) return GraphRemoteAlgorithmState.UNKNOWN;
        try {
            JsonNode root = mapper.readTree(body);
            String status = root.path("status").asText(root.path("state").asText(""));
            return switch (status.toUpperCase(java.util.Locale.ROOT)) {
                case "RUNNING", "STARTED", "PENDING" -> GraphRemoteAlgorithmState.RUNNING;
                case "SUCCEEDED", "SUCCESS", "COMPLETED", "DONE" ->
                        GraphRemoteAlgorithmState.SUCCEEDED;
                case "FAILED", "ERROR", "CANCELLED", "CANCELED" ->
                        GraphRemoteAlgorithmState.FAILED;
                default -> GraphRemoteAlgorithmState.UNKNOWN;
            };
        } catch (Exception ignored) {
            return GraphRemoteAlgorithmState.UNKNOWN;
        }
    }
}
