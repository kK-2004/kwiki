package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.ArcadeDbCapabilityReport;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对固定部署版本执行健康、版本和 Leiden 输入/输出能力探测。
 * 响应缺字段时按不支持处理，避免把未知能力当作可构建。
 */
public final class ArcadeDbCapabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(ArcadeDbCapabilityProbe.class);
    private final ArcadeDbHttpAdapter client;
    private final ArcadeDbProperties properties;
    private final ObjectMapper objectMapper;

    public ArcadeDbCapabilityProbe(ArcadeDbHttpAdapter client,
                                   ArcadeDbProperties properties,
                                   ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ArcadeDbCapabilityReport probe() {
        try {
            ArcadeDbResponse response = client.health();
            JsonNode root = objectMapper.readTree(response.body());
            String version = text(root, "serverVersion", text(root, "version", ""));
            boolean versionCompatible = versionMatches(version, properties.requiredServerVersion());
            JsonNode capabilities = root.path("capabilities");
            boolean leiden = capabilities.path("leiden").asBoolean(false)
                    && capabilities.path("leidenInputOutput").asBoolean(false);
            boolean schema = capabilities.path("schema").asBoolean(false);
            boolean databaseCreation = capabilities.path("databaseCreate").asBoolean(false)
                    || capabilities.path("createDatabase").asBoolean(false);
            String failure = firstFailure(versionCompatible, leiden, schema, databaseCreation);
            return new ArcadeDbCapabilityReport(true, version, versionCompatible, leiden, schema,
                    databaseCreation, failure.isEmpty(), failure);
        } catch (ArcadeDbClientException e) {
            log.warn("ArcadeDB 能力探测失败 category={}", e.category());
            return unavailable(e.category().name().toLowerCase());
        } catch (Exception e) {
            log.warn("ArcadeDB 能力探测响应无效 errorClass={}", e.getClass().getSimpleName());
            return unavailable("invalid-capability-response");
        }
    }

    private ArcadeDbCapabilityReport unavailable(String failure) {
        return new ArcadeDbCapabilityReport(false, "", false, false, false, false, false, failure);
    }

    private static String firstFailure(boolean versionCompatible, boolean leiden,
                                       boolean schema, boolean databaseCreation) {
        if (!versionCompatible) return "unsupported-server-version";
        if (!leiden) return "leiden-capability-missing";
        if (!schema) return "schema-capability-missing";
        if (!databaseCreation) return "database-create-permission-missing";
        return "";
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : fallback;
    }

    private static boolean versionMatches(String actual, String required) {
        if (actual == null || actual.isBlank() || required == null || required.isBlank()) {
            return false;
        }
        String normalizedActual = actual.startsWith("v") ? actual.substring(1) : actual;
        String normalizedRequired = required.startsWith("v") ? required.substring(1) : required;
        return normalizedActual.equals(normalizedRequired)
                || normalizedActual.startsWith(normalizedRequired + ".");
    }
}
