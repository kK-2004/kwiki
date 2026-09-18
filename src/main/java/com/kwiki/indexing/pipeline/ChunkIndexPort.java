package com.kwiki.indexing.pipeline;

/** 索引构建工作线程使用的索引写入边界（由 Elasticsearch 适配器实现）。 */
public interface ChunkIndexPort {

    /**
     * 幂等地写入带版本号的父/子分块集合。
     * 写入目标必须是显式验证过的物理索引名，绝不接受读别名。
     */
    void upsertChunks(IndexedVersion version, String physicalIndex);

    /** 从指定物理索引中移除该资源的所有分块。 */
    void deleteResourceChunks(String physicalIndex, String resourceType, long resourceId);
}
