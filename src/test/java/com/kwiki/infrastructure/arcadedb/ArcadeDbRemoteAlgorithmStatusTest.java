package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.persistence.GraphRemoteAlgorithmState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArcadeDbRemoteAlgorithmStatusTest {

    @Test
    void unknownPayloadKeepsTheRemoteSlot() {
        assertThat(ArcadeDbRemoteAlgorithmStatus.parse("{\"status\":\"later\"}",
                new ObjectMapper())).isEqualTo(GraphRemoteAlgorithmState.UNKNOWN);
        assertThat(ArcadeDbRemoteAlgorithmStatus.parse("{\"state\":\"completed\"}",
                new ObjectMapper())).isEqualTo(GraphRemoteAlgorithmState.SUCCEEDED);
    }
}
