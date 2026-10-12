package com.kwiki.infrastructure.ai.registry;

import java.util.Set;

/** provider 下登记的一个模型；modelName 为发给 API 的名称。 */
public record ModelDefinition(Long id, long providerId, String modelName, String displayName,
                              Set<ModelTag> tags, Integer dimensions, Integer maxOutputTokens,
                              boolean supportsTools, boolean supportsThinking, boolean enabled) {

    public ModelDefinition {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public boolean has(ModelTag tag) {
        return tags.contains(tag);
    }

    public ModelDefinition withId(long newId) {
        return new ModelDefinition(newId, providerId, modelName, displayName, tags, dimensions,
                maxOutputTokens, supportsTools, supportsThinking, enabled);
    }
}
