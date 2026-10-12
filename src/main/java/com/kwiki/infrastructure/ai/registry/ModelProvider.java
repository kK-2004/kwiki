package com.kwiki.infrastructure.ai.registry;

/** 一个 OpenAI-compatible 服务提供方；apiKeyCipher 为 AES-GCM 密文，绝不外泄。 */
public record ModelProvider(Long id, String name, String protocol, String baseUrl,
                            String apiKeyCipher, String apiKeyHint, int requestTimeoutMs,
                            int maxRetries, Integer maxConcurrency, Double qps, boolean enabled) {

    public static final String OPENAI_COMPATIBLE = "openai_compatible";

    public ModelProvider withId(long newId) {
        return new ModelProvider(newId, name, protocol, baseUrl, apiKeyCipher, apiKeyHint,
                requestTimeoutMs, maxRetries, maxConcurrency, qps, enabled);
    }
}
