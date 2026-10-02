package com.kwiki.indexing.plugin;

/** 索引流水线插件（字段格式、解析器、分块器）的公共契约；实现类为 Spring Bean，按 id 注册。 */
public interface IndexPlugin {
    /** 全局唯一标识，持久化在索引版本配置中，一经使用不得更改语义。 */
    String id();

    /** 管理端显示名。 */
    String label();

    /** 当前部署下是否可用；不可用时原因不得包含密钥值。 */
    default PluginAvailability availability() {
        return PluginAvailability.ok();
    }
}
