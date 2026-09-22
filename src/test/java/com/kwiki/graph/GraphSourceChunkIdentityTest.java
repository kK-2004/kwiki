package com.kwiki.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphSourceChunkIdentityTest {

    @Test
    void sameChunkKeyWithDifferentPipelineOrContentGetsDifferentIdentity() {
        GraphSourceChunk first = source("parser-v1", "hash-a");
        GraphSourceChunk differentPipeline = source("parser-v2", "hash-a");
        GraphSourceChunk differentContent = source("parser-v1", "hash-b");

        assertThat(first.sourceChunkId()).isEqualTo(source("parser-v1", "hash-a").sourceChunkId());
        assertThat(first.sourceChunkId()).isNotEqualTo(differentPipeline.sourceChunkId());
        assertThat(first.sourceChunkId()).isNotEqualTo(differentContent.sourceChunkId());
    }

    @Test
    void communityIdentityIncludesKnowledgeBaseAndGraphVersion() {
        assertThat(new GraphCommunityIdentity(7, 42, "17").value()).isEqualTo("7:42:17");
        assertThat(new GraphCommunityIdentity(8, 42, "17").value())
                .isNotEqualTo(new GraphCommunityIdentity(7, 42, "17").value());
    }

    private static GraphSourceChunk source(String parserVersion, String contentHash) {
        return new GraphSourceChunk(7, "PAGE", 11, 3L, 4, 5,
                parserVersion, "chunker-v1", "chunk-0", contentHash);
    }
}
