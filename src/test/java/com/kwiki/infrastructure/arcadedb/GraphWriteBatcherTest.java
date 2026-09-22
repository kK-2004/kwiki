package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphWriteBatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void splitsAtFiveHundredItems() {
        var values = IntStream.range(0, 501).boxed().toList();

        var batches = GraphWriteBatcher.split(values, mapper);

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(500);
        assertThat(batches.get(1)).containsExactly(500);
    }

    @Test
    void rejectsOneRecordLargerThanFiveMiB() {
        String value = "x".repeat(GraphWriteBatcher.MAX_BYTES + 1);

        assertThatThrownBy(() -> GraphWriteBatcher.split(java.util.List.of(value), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 MiB");
    }
}
