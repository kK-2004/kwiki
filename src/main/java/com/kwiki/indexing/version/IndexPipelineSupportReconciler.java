package com.kwiki.indexing.version;

import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 流水线受支持性对账（任务 5.5）：启动时（及管理员触发再对账时）
 * 检查每个 selected/writeEnabled 版本的 built 配置；流水线或凭据不可
 * 用时标记 pipelineSupported=false 并写入脱敏健康摘要——该版本被
 * 禁止选择切换，管理端列出缺失的依赖。恢复支持或停用该版本是仅有
 * 的两条出路。读路径不受影响。
 */
@Component
public class IndexPipelineSupportReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexPipelineSupportReconciler.class);

    private final ObjectProvider<SearchIndexVersionRepository> versions;
    private final VersionedIndexingPipelineRegistry registry;

    public IndexPipelineSupportReconciler(ObjectProvider<SearchIndexVersionRepository> versions,
                                          VersionedIndexingPipelineRegistry registry) {
        this.versions = versions;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconcile();
    }

    /** 返回发生状态变化的版本数；注册表不可用时失败关闭（不静默全标）。 */
    public int reconcile() {
        SearchIndexVersionRepository repository = versions.getIfAvailable();
        if (repository == null) {
            return 0;
        }
        int changed = 0;
        for (SearchIndexVersion version : repository.findByDeletedAtIsNullOrderByVersionNumberAsc()) {
            // 全部未删除版本都保持支持性标记最新：管理端重建前预检、
            // 切换门禁与 UNSUPPORTED 展示都读取该标记。
            boolean shouldSupport = registry.supports(version.editableConfig());
            if (version.isPipelineSupported() != shouldSupport) {
                IndexVersionSnapshot snapshot = IndexVersionStatusPolicy.pipelineSupportChanged(
                        version.toSnapshot(false, false), shouldSupport);
                version.applySnapshot(snapshot);
                repository.save(version);
                changed++;
                if (!shouldSupport && (version.isSelected() || version.isWriteEnabled())) {
                    log.warn("index version {} pipeline or credential unavailable: {};"
                                    + " switching to it is blocked",
                            version.getVersionNumber(),
                            registry.unsupportedReason(version.editableConfig()).orElse("unknown"));
                } else {
                    log.info("index version {} pipeline support restored", version.getVersionNumber());
                }
            }
        }
        return changed;
    }
}
