package com.kwiki.infrastructure.ai.registry;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 某一配置版本下目录的完整读取结果。 */
public record ModelCatalog(long version, List<ModelProvider> providers, List<ModelDefinition> models,
                           Map<ModelPurpose, ModelAssignment> assignments) {

    public ModelCatalog {
        providers = List.copyOf(providers);
        models = List.copyOf(models);
        EnumMap<ModelPurpose, ModelAssignment> complete = new EnumMap<>(ModelPurpose.class);
        for (ModelPurpose purpose : ModelPurpose.values()) {
            complete.put(purpose, assignments.getOrDefault(purpose, ModelAssignment.unassigned(purpose)));
        }
        assignments = Map.copyOf(complete);
    }

    public Optional<ModelProvider> provider(long id) {
        return providers.stream().filter(p -> p.id() == id).findFirst();
    }

    public Optional<ModelProvider> providerNamed(String name) {
        return providers.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public Optional<ModelDefinition> model(long id) {
        return models.stream().filter(m -> m.id() == id).findFirst();
    }
}
