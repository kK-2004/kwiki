package com.kwiki.infrastructure.ai.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * 构建一个对话客户端所需的全部取值；指纹相同的客户端在快照之间复用。
 * providerName 与 step 用于用量归属，不同步骤各有独立客户端。
 */
public record ClientSpec(String baseUrl, String apiKey, Duration timeout, String modelName,
                         double temperature, Integer maxTokens, Boolean thinking, Map<String, Object> parameters,
                         String providerName, String step) {

    public ClientSpec {
        parameters = parameters == null ? Map.of() : parameters;
        providerName = providerName == null ? "" : providerName;
        step = step == null ? "" : step;
    }

    public ClientSpec(String baseUrl, String apiKey, Duration timeout, String modelName,
                      double temperature, Integer maxTokens, Boolean thinking, Map<String, Object> parameters) {
        this(baseUrl, apiKey, timeout, modelName, temperature, maxTokens, thinking, parameters, "", "");
    }

    public ClientSpec(String baseUrl, String apiKey, Duration timeout, String modelName,
                      double temperature, Integer maxTokens, Boolean thinking) {
        this(baseUrl, apiKey, timeout, modelName, temperature, maxTokens, thinking, Map.of(), "", "");
    }

    /** 透传给 provider 的请求体参数：extra 透传部分，再加上 thinking 开关。 */
    public Map<String, Object> customParameters() {
        Map<String, Object> merged = new java.util.TreeMap<>(parameters);
        if (thinking != null) merged.put("enable_thinking", thinking);
        return merged;
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = String.join("\u0000", baseUrl, apiKey, String.valueOf(timeout.toMillis()),
                    modelName, String.valueOf(temperature), String.valueOf(maxTokens), String.valueOf(thinking),
                    String.valueOf(new java.util.TreeMap<>(parameters)), providerName, step);
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @Override
    public String toString() {
        return "ClientSpec[baseUrl=" + baseUrl + ", model=" + modelName + ", apiKey=****]";
    }
}
