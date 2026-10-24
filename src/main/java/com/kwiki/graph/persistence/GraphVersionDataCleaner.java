package com.kwiki.graph.persistence;

/** 删除某库某个 graphVersion 在图存储中的全部数据；实现隔离 ArcadeDB 细节。 */
public interface GraphVersionDataCleaner {

    /**
     * 删除 {@code (kbId, graphVersion)} 的全部顶点（连带其边）。幂等：数据已不存在视为成功。
     * 失败抛出异常，调用方记录并允许重试。
     */
    void deleteGraphVersion(long kbId, long graphVersion);
}
