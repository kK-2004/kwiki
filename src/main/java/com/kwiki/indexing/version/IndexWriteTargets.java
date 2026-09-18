package com.kwiki.indexing.version;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 当前 writeEnabled 且未删除的物理写入目标集合。供入队 SQL 之外的
 * 同步删除路径（归档同步、回收站清理）使用；事件入队扇出在
 * JdbcIndexingJobEnqueuer 的 SQL 内联完成，与这里读取同一事实表。
 */
@Component
public class IndexWriteTargets {

    /** 一个物理写入目标：版本号 + 显式物理索引名。 */
    public record WriteTarget(int versionNumber, String physicalName) {
    }

    private final ObjectProvider<SearchIndexVersionRepository> versions;

    public IndexWriteTargets(ObjectProvider<SearchIndexVersionRepository> versions) {
        this.versions = versions;
    }

    public List<WriteTarget> current() {
        SearchIndexVersionRepository repository = versions.getIfAvailable();
        if (repository == null) {
            return List.of();
        }
        return repository.findByWriteEnabledTrueAndDeletedAtIsNull().stream()
                .map(version -> new WriteTarget(version.getVersionNumber(),
                        version.getPhysicalName()))
                .toList();
    }
}
