package com.kwiki.graph;

/** 社区发现端口，首版只允许固定的原生无权模式。 */
public interface CommunityDetectionPort {

    CommunityDetectionResult detect(CommunityDetectionInput input);
}
