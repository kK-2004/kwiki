package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 版本注册表的核心操作：单调分配下一个版本号（tombstone 行保留，
 * 号段永不回退/复用）与离线版本的配置编辑（原子递增 configRevision、
 * 刷新派生 mappingHash）。管理端校验（受支持清单、容量预检、审计、
 * 幂等键）在上层服务叠加；这里只保证注册表自身的不变量。
 *
 * <p>注册表经 ObjectProvider 注入：离线测试上下文（JPA 排除）可以
 * 启动，任何真正需要注册表的操作显式失败。
 */
@Service
public class SearchIndexVersionService {

    private static final int ALLOCATION_ATTEMPTS = 5;

    private final ObjectProvider<SearchIndexVersionRepository> versions;
    private final ChunkMappingBuilder mappingBuilder;

    public SearchIndexVersionService(ObjectProvider<SearchIndexVersionRepository> versions,
                                     ChunkMappingBuilder mappingBuilder) {
        this.versions = versions;
        this.mappingBuilder = mappingBuilder;
    }

    private SearchIndexVersionRepository registry() {
        SearchIndexVersionRepository repository = versions.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("search index version registry is unavailable");
        }
        return repository;
    }

    /**
     * 分配下一个版本并持久化。并发创建依赖数据库唯一约束：冲突时
     * 重读当前最大号重试；重试耗尽说明高并发争抢，显式失败。
     */
    @Transactional
    public SearchIndexVersion createNext(EditableIndexConfig config) {
        SearchIndexVersionRepository registry = registry();
        DataIntegrityViolationException lastContention = null;
        for (int attempt = 0; attempt < ALLOCATION_ATTEMPTS; attempt++) {
            Integer max = registry.findMaxVersionNumber();
            int next = max == null ? 1 : max + 1;
            try {
                return registry.save(new SearchIndexVersion(next,
                        "kwiki-chunks-v" + next, config,
                        mappingBuilder.mappingHash(config.embeddingDimensions())));
            } catch (DataIntegrityViolationException raced) {
                lastContention = raced;
            }
        }
        throw new IllegalStateException(
                "version number allocation contention was not resolved", lastContention);
    }

    /** 编辑离线版本配置：在线/写入/运行中版本由策略拒绝。 */
    @Transactional
    public SearchIndexVersion edit(int versionNumber, EditableIndexConfig config) {
        SearchIndexVersion version = registry().findByVersionNumber(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + versionNumber));
        IndexVersionStatusPolicy.editConfig(version.toSnapshot(false, false), null);
        version.applyConfigEdit(config,
                mappingBuilder.mappingHash(config.embeddingDimensions()));
        return registry().save(version);
    }
}
