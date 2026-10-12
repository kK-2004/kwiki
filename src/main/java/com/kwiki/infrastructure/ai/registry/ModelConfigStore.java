package com.kwiki.infrastructure.ai.registry;

import java.util.Collection;
import java.util.List;

/** 模型目录持久化边界。写方法不负责递增版本号，由调用方在同一事务中调用 {@link #bumpVersion()}。 */
public interface ModelConfigStore {

    ModelCatalog load();

    long currentVersion();

    /** 递增并返回新的配置版本号。 */
    long bumpVersion();

    boolean isEmpty();

    long insertProvider(ModelProvider provider);

    void updateProvider(ModelProvider provider);

    void deleteProvider(long id);

    long insertModel(ModelDefinition model);

    void updateModel(ModelDefinition model);

    void deleteModel(long id);

    void saveAssignments(Collection<ModelAssignment> assignments);

    /** provider 改名时同步更新索引版本中按名称的引用，返回受影响行数。 */
    int renameEmbeddingProviderReferences(String from, String to);

    /** 引用该 embedding 模型的未删除索引版本号。 */
    List<Integer> indexVersionsReferencing(String providerName, String modelName);

    /** 在当前事务内锁住版本行（SELECT ... FOR UPDATE），用于跨实例串行化首次导入。 */
    void lockVersionRow();
}
