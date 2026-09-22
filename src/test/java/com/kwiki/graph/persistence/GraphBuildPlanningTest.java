package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphBuildPlanningTest {

    @Test
    void manifestSortsSourcesAndDoesNotHideMissingChunkTargets() {
        GraphSourceChunk second = source("c2");
        GraphSourceChunk first = source("c1");
        GraphSourceManifest manifest = new GraphSourceManifestBuilder().build(
                3, 1, 3, "entity-linking-v1", 2, 3, 7, true,
                List.of(second, first), Map.of(first.sourceChunkId(), GraphSourceReadiness.READY));

        assertThat(manifest.entries()).extracting(entry -> entry.sourceChunk().chunkKey())
                .containsExactly("c1", "c2");
        assertThat(manifest.entries().get(1).readiness())
                .isEqualTo(GraphSourceReadiness.WAITING_FOR_CHUNKS);
        assertThat(manifest.complete()).isFalse();
    }

    @Test
    void manifestRejectsCrossKnowledgeBaseSource() {
        assertThatThrownBy(() -> new GraphSourceManifestBuilder().build(
                3, 1, 3, "entity-linking-v1", 0, 0, 0, true,
                List.of(source(2, "c1")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GraphSourceChunk source(String key) {
        return source(1, key);
    }

    private static GraphSourceChunk source(long kbId, String key) {
        return new GraphSourceChunk(kbId, "PAGE", 9, 4L, 2, 3,
                "parser-1", "chunker-1", key, "0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef");
    }
}
