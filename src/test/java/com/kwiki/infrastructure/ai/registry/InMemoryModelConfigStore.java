package com.kwiki.infrastructure.ai.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试用内存目录：行为与 JdbcModelConfigStore 的约定一致。 */
class InMemoryModelConfigStore implements ModelConfigStore {

    final Map<Long, ModelProvider> providers = new LinkedHashMap<>();
    final Map<Long, ModelDefinition> models = new LinkedHashMap<>();
    final Map<ModelPurpose, ModelAssignment> assignments = new EnumMap<>(ModelPurpose.class);
    /** 索引版本号 → {provider 名, 模型名}。 */
    final Map<Integer, String[]> indexVersions = new LinkedHashMap<>();
    long version;
    long nextId = 1;
    int locks;

    @Override
    public ModelCatalog load() {
        return new ModelCatalog(version, new ArrayList<>(providers.values()), new ArrayList<>(models.values()),
                assignments);
    }

    @Override
    public long currentVersion() {
        return version;
    }

    @Override
    public long bumpVersion() {
        return ++version;
    }

    @Override
    public boolean isEmpty() {
        return providers.isEmpty();
    }

    @Override
    public long insertProvider(ModelProvider provider) {
        long id = nextId++;
        providers.put(id, provider.withId(id));
        return id;
    }

    @Override
    public void updateProvider(ModelProvider provider) {
        providers.put(provider.id(), provider);
    }

    @Override
    public void deleteProvider(long id) {
        providers.remove(id);
    }

    @Override
    public long insertModel(ModelDefinition model) {
        long id = nextId++;
        models.put(id, model.withId(id));
        return id;
    }

    @Override
    public void updateModel(ModelDefinition model) {
        models.put(model.id(), model);
    }

    @Override
    public void deleteModel(long id) {
        models.remove(id);
    }

    @Override
    public void saveAssignments(Collection<ModelAssignment> values) {
        values.forEach(a -> assignments.put(a.purpose(), a));
    }

    @Override
    public int renameEmbeddingProviderReferences(String from, String to) {
        int changed = 0;
        for (String[] ref : indexVersions.values()) {
            if (ref[0].equals(from)) {
                ref[0] = to;
                changed++;
            }
        }
        return changed;
    }

    @Override
    public List<Integer> indexVersionsReferencing(String providerName, String modelName) {
        return indexVersions.entrySet().stream()
                .filter(e -> e.getValue()[0].equals(providerName) && e.getValue()[1].equals(modelName))
                .map(Map.Entry::getKey).toList();
    }

    @Override
    public void lockVersionRow() {
        locks++;
    }
}
