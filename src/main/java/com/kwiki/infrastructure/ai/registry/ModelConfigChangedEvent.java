package com.kwiki.infrastructure.ai.registry;

/** 目录写入后发布；监听方在事务提交后重新加载并广播。 */
public record ModelConfigChangedEvent(long version) {
}
