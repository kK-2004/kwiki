package com.kwiki.indexing.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BootstrapMigrationRecorderTest {

    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, 1);
    private final String hash = new ChunkMappingBuilder().mappingHash(1024, 1);

    @Test
    void 首次部署写入已完成且双写起点为0的迁移记录_满足校验的同步条件() throws Exception {
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SearchIndexVersion v1 = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);

        new BootstrapMigrationRecorder(runs, new ObjectMapper()).recordEmptyBaseline(v1);

        ArgumentCaptor<SearchIndexRebuildRun> saved = ArgumentCaptor.forClass(SearchIndexRebuildRun.class);
        verify(runs).save(saved.capture());
        SearchIndexRebuildRun run = saved.getValue();
        assertThat(run.kind()).isEqualTo(RebuildRunKind.MIGRATION);
        assertThat(run.state()).isEqualTo(RebuildRunState.COMPLETED);
        assertThat(run.getBuildStartEventId()).isZero();
        assertThat(run.getBuildStartEventId()).isEqualTo(v1.getWriteEnabledEventId());
        assertThat(run.getConfigRevision()).isEqualTo(v1.getConfigRevision());
        assertThat(run.getLeaseOwner()).isNull();
        // 构建清单与版本一致：校验的 manifest 项据此通过
        BuildManifestSnapshot manifest = new ObjectMapper().readValue(run.getBuildManifest(), BuildManifestSnapshot.class);
        assertThat(manifest.mappingHash()).isEqualTo(hash);
        assertThat(manifest.physicalName()).isEqualTo("kwiki-chunks-v1");
    }

    @Test
    void 写入未开启的版本不能写基线() {
        SearchIndexVersion offline = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        assertThatThrownBy(() -> new BootstrapMigrationRecorder(runs, new ObjectMapper()).recordEmptyBaseline(offline))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(runs);
    }
}
