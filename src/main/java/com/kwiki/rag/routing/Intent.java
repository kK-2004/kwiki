package com.kwiki.rag.routing;

/**
 * Terminal routing intents. Graph-specific intents and tools from k-Rag are
 * deliberately excluded: kwiki has no Neo4j or Cypher capability.
 */
public enum Intent {
    DIRECT_ANSWER,
    KNOWLEDGE_QA,
    PROCEDURAL,
    ANALYTICAL
}
