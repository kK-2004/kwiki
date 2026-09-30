package com.kwiki.indexing.version;

/**
 * 重建 run 种类：INITIAL 为部署引导的首次基线构建，MANUAL 为管理端
 * 触发的原地重建，MIGRATION：写入开启后按版本解析器补入双写起点之前的历史数据。
 */
public enum RebuildRunKind {
    INITIAL, MANUAL, MIGRATION
}
