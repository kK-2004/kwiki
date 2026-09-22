package com.kwiki.graph;

/** 知识库内实体注册和保守消歧的持久化边界。 */
public interface GraphEntityRegistryPort {

    GraphEntity findOrRegister(long kbId, GraphEntity candidate,
                               String contextFingerprint, String resolverVersion);
}
