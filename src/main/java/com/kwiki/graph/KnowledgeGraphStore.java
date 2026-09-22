package com.kwiki.graph;

/** 图投影存储端口，隔离 ArcadeDB 的 RID、SQL 和 HTTP 细节。 */
public interface KnowledgeGraphStore {

    GraphWriteResult write(GraphWriteRequest request);
}
