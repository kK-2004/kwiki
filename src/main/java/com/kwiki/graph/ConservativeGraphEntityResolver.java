package com.kwiki.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 只有规范名称、类型、版本和上下文指纹均匹配时才复用实体 ID。 */
public final class ConservativeGraphEntityResolver implements GraphEntityResolutionPort {

    private final GraphEntityRegistryPort registry;

    public ConservativeGraphEntityResolver(GraphEntityRegistryPort registry) {
        this.registry = registry;
    }

    @Override
    public List<GraphEntity> resolve(long kbId, List<GraphEntity> candidates,
                                     String context, String resolverVersion) {
        if (kbId <= 0 || resolverVersion == null || resolverVersion.isBlank()) {
            throw new IllegalArgumentException("实体消歧身份无效");
        }
        String normalizedContext = context == null ? "" : context.strip();
        return candidates == null ? List.of() : candidates.stream()
                .map(candidate -> {
                    String fingerprint = sha256(normalizedContext + "\n" + candidate.canonicalName());
                    return registry.findOrRegister(kbId, candidate, fingerprint, resolverVersion);
                })
                .toList();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算实体上下文指纹", e);
        }
    }
}
