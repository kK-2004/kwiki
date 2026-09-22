package com.kwiki.graph.persistence;

/** 取消/超时后的状态决策，不把 HTTP future 取消伪装成远端算法已停止。 */
public final class GraphRemoteAlgorithmGuard {

    private GraphRemoteAlgorithmGuard() {
    }

    public static boolean canCleanup(GraphRemoteAlgorithmState state) {
        return state == GraphRemoteAlgorithmState.SUCCEEDED
                || state == GraphRemoteAlgorithmState.FAILED;
    }

    public static GraphBuildState stateAfterProbe(GraphRemoteAlgorithmState state) {
        return state == GraphRemoteAlgorithmState.UNKNOWN
                ? GraphBuildState.NEEDS_ATTENTION
                : state == GraphRemoteAlgorithmState.FAILED
                        ? GraphBuildState.FAILED : GraphBuildState.CLUSTERING;
    }
}
