package com.kwiki.graph.persistence;

import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 批次删除与快照退役共用的外部数据清理：按资源种类分派、失败上抛。 */
class GraphArtifactCleanerTest {

    @Test
    void graphVersionResourceDeletesThatVersionOnly() {
        GraphVersionDataCleaner graph = mock(GraphVersionDataCleaner.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);

        assertThat(new GraphArtifactCleaner(graph, indexes)
                .delete(GraphArtifactCleaner.KIND_GRAPH_VERSION, GraphArtifactCleaner.graphVersionIdentity(7, 43)))
                .isTrue();

        verify(graph).deleteGraphVersion(7L, 43L);
        verifyNoInteractions(indexes);
    }

    @Test
    void communityIndexResourceDeletesThePhysicalIndex() throws Exception {
        GraphVersionDataCleaner graph = mock(GraphVersionDataCleaner.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);

        new GraphArtifactCleaner(graph, indexes)
                .delete(GraphArtifactCleaner.KIND_COMMUNITY_INDEX, "kwiki-communities-v3-kb7");

        verify(indexes).forceDeleteManagedIndex("kwiki-communities-v3-kb7");
        verifyNoInteractions(graph);
    }

    @Test
    void elasticsearchFailureIsRethrownWithItsMessage() throws Exception {
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        doThrow(new java.io.IOException("es down")).when(indexes).forceDeleteManagedIndex("kwiki-communities-v3-kb7");

        assertThatThrownBy(() -> new GraphArtifactCleaner(null, indexes).deleteCommunityIndex("kwiki-communities-v3-kb7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("es down");
    }

    @Test
    void unknownKindOrMalformedIdentityIsRejected() {
        GraphArtifactCleaner cleaner = new GraphArtifactCleaner(mock(GraphVersionDataCleaner.class), null);

        assertThatThrownBy(() -> cleaner.delete("SOMETHING_ELSE", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleaner.delete(GraphArtifactCleaner.KIND_GRAPH_VERSION, "43"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingStoresMeanNothingToDelete() {
        GraphArtifactCleaner cleaner = new GraphArtifactCleaner((GraphVersionDataCleaner) null, null);

        assertThat(cleaner.delete(GraphArtifactCleaner.KIND_GRAPH_VERSION, "7:43")).isTrue();
        assertThat(cleaner.delete(GraphArtifactCleaner.KIND_COMMUNITY_INDEX, "kwiki-communities-v3-kb7")).isTrue();
    }
}
