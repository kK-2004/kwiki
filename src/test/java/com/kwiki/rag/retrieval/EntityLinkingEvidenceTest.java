package com.kwiki.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityLinkingEvidenceTest {

    @Test
    void oldIndexMetadataIsMissingAndReadyIdsAreStable() {
        ChunkHit old = new ChunkHit("c", "p", 1, "PAGE", 2, 3L,
                "h", 0, 1, "text");
        assertThat(old.entityLinkingStatus()).isEqualTo(EntityLinkingStatus.MISSING);

        ChunkHit ready = new ChunkHit("c", "p", 1, "PAGE", 2, 3L,
                "h", 0, 1, "text", List.of(), "sc_x",
                List.of("e2", "e1", "e2"), "entity-linking-v1",
                EntityLinkingStatus.READY);
        ChildEvidence evidence = ChildEvidence.fromHit(ready, 1, null, 1.0);
        assertThat(evidence.entityIds()).containsExactly("e1", "e2");
        assertThat(evidence.entityLinkingStatus()).isEqualTo(EntityLinkingStatus.READY);
        assertThat(evidence.sourceChunkId()).isEqualTo("sc_x");
    }
}
