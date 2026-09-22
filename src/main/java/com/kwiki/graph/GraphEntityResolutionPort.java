package com.kwiki.graph;

import java.util.List;

/** 把抽取候选固定映射为知识库内实体身份。 */
public interface GraphEntityResolutionPort {

    List<GraphEntity> resolve(long kbId, List<GraphEntity> candidates,
                              String context, String resolverVersion);
}
