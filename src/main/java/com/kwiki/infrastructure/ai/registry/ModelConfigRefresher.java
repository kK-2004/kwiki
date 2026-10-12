package com.kwiki.infrastructure.ai.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 驱动 {@link DefaultModelRegistry} 的加载与刷新：启动时先执行首次导入再加载；
 * 本机写入在事务提交后立即重新加载并广播；收到更高版本号的广播时重新加载；
 * 另以固定间隔比对数据库版本号兜底，防止广播丢失或未启用 Redis。
 */
@Component
@EnableScheduling
public class ModelConfigRefresher implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigRefresher.class);

    private final DefaultModelRegistry registry;
    private final ModelConfigStore store;
    private final ObjectProvider<ModelConfigBroadcaster> broadcaster;
    private final ObjectProvider<ModelConfigBootstrap> importer;

    public ModelConfigRefresher(DefaultModelRegistry registry, ModelConfigStore store,
                                ObjectProvider<ModelConfigBroadcaster> broadcaster,
                                ObjectProvider<ModelConfigBootstrap> importer) {
        this.registry = registry;
        this.store = store;
        this.broadcaster = broadcaster;
        this.importer = importer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ModelConfigBootstrap bootstrap = importer.getIfAvailable();
        if (bootstrap != null) {
            try {
                bootstrap.importIfEmpty();
            } catch (RuntimeException e) {
                // 导入失败不阻止启动：各用途显示为未配置，管理员可在后台补录
                log.error("model config bootstrap import failed: {}",
                        com.kwiki.infrastructure.observability.SecretRedaction.redact(e.getMessage()));
            }
        }
        registry.reload();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLocalChange(ModelConfigChangedEvent event) {
        registry.reload();
        ModelConfigBroadcaster target = broadcaster.getIfAvailable();
        if (target != null) {
            try {
                target.publish(event.version());
            } catch (RuntimeException e) {
                log.warn("model config broadcast failed version={}; peers will catch up by polling", event.version());
            }
        }
    }

    /** 收到其他实例的广播。 */
    public void onRemoteVersion(long version) {
        if (version > registry.loadedVersion()) {
            registry.reload();
        }
    }

    @Scheduled(initialDelayString = "${kwiki.model-config.poll-interval:30s}",
            fixedDelayString = "${kwiki.model-config.poll-interval:30s}")
    public void poll() {
        try {
            long current = store.currentVersion();
            if (current != registry.loadedVersion()) {
                registry.reload();
            }
        } catch (RuntimeException e) {
            log.debug("model config version poll failed: {}", e.getMessage());
        }
    }
}
