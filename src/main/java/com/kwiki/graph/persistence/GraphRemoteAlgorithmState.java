package com.kwiki.graph.persistence;

/** 远端算法超时后的保守状态；UNKNOWN 必须保留算法槽位和临时库。 */
public enum GraphRemoteAlgorithmState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
