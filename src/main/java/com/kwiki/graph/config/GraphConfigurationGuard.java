package com.kwiki.graph.config;

import org.springframework.context.annotation.Configuration;

/** 在应用启动期执行图功能与 ArcadeDB 配置的组合校验。 */
@Configuration
public class GraphConfigurationGuard {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GraphConfigurationGuard.class);

    public GraphConfigurationGuard(GraphProperties graph, ArcadeDbProperties arcadeDb) {
        arcadeDb.requireUsableWhenEnabled(graph);
        if (graph != null && graph.isEnabled() && !arcadeDb.serverVersionPinned()) {
            log.warn("未锁定 ArcadeDB 版本（kwiki.external.arcadedb.required-server-version 为空）："
                    + "将跳过服务版本兼容性检查，ArcadeDB 升级后 Leiden 等行为变化不会被发现；"
                    + "生产环境建议锁定为已验证的版本");
        }
    }
}
