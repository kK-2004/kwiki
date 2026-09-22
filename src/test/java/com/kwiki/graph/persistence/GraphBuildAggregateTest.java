package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphBuildAggregateTest {

    @Test
    void oneFailedKnowledgeBaseDoesNotHideReadySiblings() {
        assertThat(GraphBuildAggregate.aggregate(List.of(
                GraphBuildState.READY, GraphBuildState.FAILED)))
                .isEqualTo(GraphBuildState.PARTIAL_FAILED);
    }
}
