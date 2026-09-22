package com.kwiki.graph;

/** 图服务的有界邻接查询端口。 */
public interface GraphExpansionPort {
    GraphExpansionResult expand(GraphExpansionRequest request);
}
