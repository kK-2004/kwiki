package com.kwiki.graph;

/** 图投影存储端口，隔离 ArcadeDB 的 RID、SQL 和 HTTP 细节。 */
public interface KnowledgeGraphStore {

    GraphWriteResult write(GraphWriteRequest request);

    /** 把社区检测结果回写到对应图版本的实体上。 */
    void assignCommunities(long kbId, long graphVersion, java.util.List<CommunityMembership> memberships);
}
