package com.kwiki.indexing.version;

/** 一次成功基线构建之后的切换准备状态；验证与别名提交另行持久化。 */
public enum IndexSwitchState {
    NONE, PREPARING, READY, FAILED
}
