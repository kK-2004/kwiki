package com.kwiki.infrastructure.ai.registry;

/** 把新的配置版本号通知给其他实例。 */
public interface ModelConfigBroadcaster {

    String CHANNEL = "kwiki:model-config";

    void publish(long version);
}
