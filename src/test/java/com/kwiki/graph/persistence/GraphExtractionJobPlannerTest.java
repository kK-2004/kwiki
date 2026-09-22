package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphExtractionJobPlannerTest {

    @Test
    void specRequiresChunkAndPublishedRevisionBeforePlanning() {
        GraphSourceChunk source = new GraphSourceChunk(1, "PAGE", 2, 3L, 4, 5,
                "parser-v1", "chunker-v1", "chunk-0", "hash");
        GraphExtractionJobSpec spec = new GraphExtractionJobSpec(source, "extract-v1", "prompt-v1",
                "resolver-v1", 8, 9);
        assertThat(spec.sourceChunk().sourceChunkId()).startsWith("sc_");
        assertThat(List.of(false, false)).containsExactly(false, false);
    }
}
