package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 一库多个 Chunk 版本配对时，新鲜度只展示一个确定的配对。 */
class GraphFreshnessServiceTest {

    private static GraphFreshnessService.PublicationRow row(int chunkVersion) {
        return new GraphFreshnessService.PublicationRow(7, chunkVersion, 100L + chunkVersion);
    }

    @Test
    void selectedChunkVersionWinsRegardlessOfQueryOrder() {
        assertThat(GraphFreshnessService.preferred(row(3), row(4), 3)).isEqualTo(row(3));
        assertThat(GraphFreshnessService.preferred(row(4), row(3), 3)).isEqualTo(row(3));
    }

    @Test
    void withoutSelectedVersionTheHighestChunkVersionWins() {
        assertThat(GraphFreshnessService.preferred(row(3), row(4), null)).isEqualTo(row(4));
        assertThat(GraphFreshnessService.preferred(row(4), row(3), null)).isEqualTo(row(4));
        // 选中版本不在这两个配对里时同样取较高版本
        assertThat(GraphFreshnessService.preferred(row(3), row(4), 9)).isEqualTo(row(4));
    }
}
