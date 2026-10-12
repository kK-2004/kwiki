package com.kwiki.infrastructure.ai.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** 构建一个对话客户端所需的全部取值；指纹相同的客户端在快照之间复用。 */
public record ClientSpec(String baseUrl, String apiKey, Duration timeout, String modelName,
                         double temperature, Integer maxTokens, Boolean thinking) {

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = String.join("\u0000", baseUrl, apiKey, String.valueOf(timeout.toMillis()),
                    modelName, String.valueOf(temperature), String.valueOf(maxTokens), String.valueOf(thinking));
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
