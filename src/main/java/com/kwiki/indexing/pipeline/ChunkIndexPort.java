package com.kwiki.indexing.pipeline;

/** 索引构建工作线程使用的索引写入边界（由 Elasticsearch 适配器实现）。 */
public interface ChunkIndexPort {

    /** 幂等地写入带版本号的父/子分块集合。 */
    void upsertChunks(IndexedVersion version);

    /** 从活动索引中移除该资源的所有分块。 */
    void deleteResourceChunks(String resourceType, long resourceId);
}
