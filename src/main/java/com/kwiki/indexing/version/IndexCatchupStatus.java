package com.kwiki.indexing.version;

/**
 * 数据追平状态：CURRENT 表示该版本已追平到最后一个已知屏障；
 * BEHIND 表示存在未覆盖的范围尾部/事件差量（新建、停用、构建后
 * 未经历切换准备补齐都属此类）。是否需要补齐只在切换准备建立
 * 新水位后计算，因此"配置待重建"与"数据待补齐"彼此独立。
 */
public enum IndexCatchupStatus {
    CURRENT, BEHIND
}
