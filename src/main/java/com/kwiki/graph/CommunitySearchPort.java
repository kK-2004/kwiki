package com.kwiki.graph;

/** 社区 BM25/vector 检索端口。 */
public interface CommunitySearchPort {

    CommunitySearchResult search(CommunitySearchRequest request);
}
