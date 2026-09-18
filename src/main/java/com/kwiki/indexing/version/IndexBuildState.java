package com.kwiki.indexing.version;

/**
 * 物理索引版本的构建状态（独立于 dirty 与补齐状态）。
 * dirty 是 configRevision/builtConfigRevision 的派生事实，不在此枚举中。
 */
public enum IndexBuildState {
    NEW, BUILDING, BUILT, FAILED
}
