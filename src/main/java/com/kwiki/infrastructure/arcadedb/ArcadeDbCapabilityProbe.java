package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.ArcadeDbCapabilityReport;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 GET /api/v1/server?mode=basic 的版本号判断能力：ArcadeDB 从 26.3.1 起内置
 * Cypher 过程 algo.leiden。另外确认业务库与计算库都存在且 token 可访问；
 * 应用不建库（服务器命令只有 root 可用），databaseCreationAllowed 恒为 true。
 * 版本缺失或无法解析时按不支持处理，避免把未知能力当作可构建。
 */
public final class ArcadeDbCapabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(ArcadeDbCapabilityProbe.class);
    /** 内置 algo.leiden 的最低服务版本。 */
    static final int[] MIN_LEIDEN_VERSION = {26, 3, 1};
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
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
            // 实际返回形如 "26.9.1 (build <sha>/<ts>/main)"，只保留版本号部分
            String version = text(root, "serverVersion", text(root, "version", "")).trim()
                    .split("\\s+", 2)[0];
            // 未锁定版本号时不做版本比对，只校验下面的算法/schema/建库能力
            boolean versionCompatible = !properties.serverVersionPinned()
                    || versionMatches(version, properties.requiredServerVersion());
            boolean leiden = atLeast(version, MIN_LEIDEN_VERSION);
            // 两个库都需管理员预建；token 无权访问时 exists 同样返回 false
            boolean schema = leiden && client.databaseExists(properties.database())
                    && client.databaseExists(properties.scratchDatabase());
            boolean databaseCreation = true;
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
        if (!schema) return "database-missing";
        if (!databaseCreation) return "database-create-permission-missing";
        return "";
    }

    /** 解析 "26.9.1 (build ...)" 这类版本串的前三段并与下限比较；无法解析视为不满足。 */
    static boolean atLeast(String version, int[] minimum) {
        if (version == null) return false;
        var matcher = VERSION.matcher(version.trim());
        if (!matcher.find()) return false;
        int[] actual = {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))};
        for (int i = 0; i < 3; i++) {
            if (actual[i] != minimum[i]) return actual[i] > minimum[i];
        }
        return true;
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
