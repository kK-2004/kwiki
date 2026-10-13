package com.kwiki.infrastructure.ai.registry;

import java.util.Map;
import java.util.Set;

/** provider 下登记的一个模型；modelName 为发给 API 的名称，extra 见 {@link ModelExtra}。 */
public record ModelDefinition(Long id, long providerId, String modelName, String displayName,
                              Set<ModelTag> tags, Integer dimensions, Integer maxOutputTokens,
                              boolean supportsTools, boolean supportsThinking, boolean enabled,
                              Map<String, Object> extra) {

    public ModelDefinition {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        extra = extra == null ? Map.of() : extra;
    }

    public ModelDefinition(Long id, long providerId, String modelName, String displayName,
                           Set<ModelTag> tags, Integer dimensions, Integer maxOutputTokens,
                           boolean supportsTools, boolean supportsThinking, boolean enabled) {
        this(id, providerId, modelName, displayName, tags, dimensions, maxOutputTokens, supportsTools,
                supportsThinking, enabled, Map.of());
    }

    public boolean has(ModelTag tag) {
        return tags.contains(tag);
    }

    public ModelDefinition withId(long newId) {
        return new ModelDefinition(newId, providerId, modelName, displayName, tags, dimensions,
                maxOutputTokens, supportsTools, supportsThinking, enabled, extra);
    }
}
