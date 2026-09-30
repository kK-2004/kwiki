package com.kwiki.indexing.version;

/**
 * 数据追平状态：CURRENT 表示写入会话内的存量迁移已完成、双写持续追平；
 * BEHIND 表示尚未迁移或写入已关闭。
 */
public enum IndexCatchupStatus {
    CURRENT, BEHIND
}
