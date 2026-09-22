package com.kwiki.graph;

import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphSeedExtractorTest {

    @Test
    void onlyReadyMatchingGenerationProvidesSeeds() {
        ChunkHit pending = new ChunkHit("p", "pp", 1, "PAGE", 1, 1L,
                "h", 0, 1, "x", List.of(), "sc_p", List.of("e-p"),
                "entity-linking-v1", EntityLinkingStatus.PENDING);
        ChunkHit ready = new ChunkHit("c", "pc", 1, "PAGE", 1, 1L,
                "h", 0, 1, "x", List.of(), "sc_c", List.of("e2", "e1", "e2"),
                "entity-linking-v1", EntityLinkingStatus.READY);
        ChunkHit stale = new ChunkHit("s", "ps", 1, "PAGE", 1, 1L,
                "h", 0, 1, "x", List.of(), "sc_s", List.of("e-s"),
                "entity-linking-v0", EntityLinkingStatus.READY);

        assertThat(GraphSeedExtractor.extract(List.of(pending, stale, ready),
                "entity-linking-v1")).extracting(GraphSeed::entityId)
                .containsExactly("e1", "e2");
    }
}
