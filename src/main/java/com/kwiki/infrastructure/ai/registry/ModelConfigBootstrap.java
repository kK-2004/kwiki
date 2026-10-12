package com.kwiki.infrastructure.ai.registry;

/** 首次启动时把旧配置导入空目录；目录非空时什么也不做。 */
public interface ModelConfigBootstrap {

    void importIfEmpty();
}
