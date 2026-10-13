package com.kwiki.indexing.version;

import com.kk2004.common.exception.BusinessException;
import com.kwiki.graph.GraphDataPurger;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexBootstrap;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.testutil.StandardTestProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndexForceResetServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
    private final ElasticsearchIndexBootstrap bootstrap = mock(ElasticsearchIndexBootstrap.class);
    private final ChunkMappingBuilder mapping = mock(ChunkMappingBuilder.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final IndexMigrationService migrations = mock(IndexMigrationService.class);
    private final GraphDataPurger purger = mock(GraphDataPurger.class);
    private IndexForceResetService service;

    private static IndexingProperties properties(boolean mutations) {
        return new IndexingProperties(null, null, new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(mutations));
    }

    private IndexForceResetService service(boolean mutations) {
        return new IndexForceResetService(jdbc, mock(PlatformTransactionManager.class), indexes,
                StandardTestProperties.providerOf(bootstrap), mapping, versions, migrations, properties(mutations),
                StandardTestProperties.providerOf(purger));
    }

    @BeforeEach
    void setUp() throws Exception {
        service = service(true);
        when(indexes.available()).thenReturn(true);
        when(indexes.indexNameFor(1)).thenReturn("kwiki-chunks-v1");
        when(indexes.listManagedIndices()).thenReturn(List.of("kwiki-chunks-v3", "kwiki-chunks-v6", "kwiki-communities-v2-kb7"));
        when(bootstrap.firstDeploymentConfig()).thenReturn(new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1",
                "default", "text-embedding-v4", 1024, "kwiki-schema-1"));
        when(mapping.mappingHash(1024, "kwiki-schema-1")).thenReturn("hash");
        when(purger.purgeAll()).thenReturn(List.of("Entity"));
        when(migrations.migrate(1, "admin")).thenReturn(new VersionRebuildCoordinator.StartResult(true, 88L, false, "ACCEPTED"));
    }

    @Test
    void 确认词不对时什么都不做() {
        assertThatThrownBy(() -> service.reset("reset", "admin"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        verifyNoInteractions(indexes, purger, versions, migrations);
    }

    @Test
    void 管理写操作关闭时拒绝() {
        assertThatThrownBy(() -> service(false).reset(IndexForceResetService.CONFIRMATION, "admin"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        verifyNoInteractions(indexes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 有运行中的重建或图谱构建时拒绝并列出任务() {
        when(jdbc.query(startsWith("SELECT id, version_number FROM search_index_rebuild_run"), any(RowMapper.class)))
                .thenReturn(List.of("重建任务 #5（v6）"));
        assertThatThrownBy(() -> service.reset(IndexForceResetService.CONFIRMATION, "admin"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409))
                .hasMessageContaining("重建任务 #5（v6）");
        verifyNoInteractions(purger, versions, migrations);
        verify(jdbc, never()).update(startsWith("DELETE FROM"));
    }

    @Test
    void 删除全部托管索引_清空图谱与相关表_重建v1并发起迁移() throws Exception {
        var result = service.reset(" " + IndexForceResetService.CONFIRMATION + " ", "admin");

        verify(indexes).forceDeleteManagedIndex("kwiki-chunks-v3");
        verify(indexes).forceDeleteManagedIndex("kwiki-chunks-v6");
        verify(indexes).forceDeleteManagedIndex("kwiki-communities-v2-kb7");
        verify(purger).purgeAll();
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : IndexForceResetService.TABLES) {
            order.verify(jdbc).update("DELETE FROM " + table);
        }
        order.verify(jdbc).execute("SET FOREIGN_KEY_CHECKS = 1");
        assertThat(IndexForceResetService.TABLES).contains("search_index_version", "index_gray_release",
                "search_index_change_event", "community_index_version", "graph_build_run")
                .doesNotContain("model_provider", "wiki_page", "attachment", "derived_image_summary");

        ArgumentCaptor<SearchIndexVersion> saved = ArgumentCaptor.forClass(SearchIndexVersion.class);
        verify(versions).saveAndFlush(saved.capture());
        SearchIndexVersion v1 = saved.getValue();
        assertThat(v1.getVersionNumber()).isEqualTo(1);
        assertThat(v1.isSelected()).isTrue();
        assertThat(v1.isWriteEnabled()).isTrue();
        assertThat(v1.getWriteEnabledEventId()).isZero();
        assertThat(v1.getBuildState()).isEqualTo("NEW");
        assertThat(v1.getBuiltConfigRevision()).isNull();
        InOrder es = inOrder(indexes, migrations);
        es.verify(indexes).createVersionedIndex("kwiki-chunks-v1", 1024, "kwiki-schema-1");
        es.verify(indexes).createInitialAlias("kwiki-chunks-v1");
        es.verify(migrations).migrate(1, "admin");
        verify(jdbc).update(contains("'FORCE_RESET'"), eq(88L), eq("admin"), anyString(), anyString(), eq("SUCCESS"), isNull());

        assertThat(result.deletedIndices()).hasSize(3);
        assertThat(result.migrationRunId()).isEqualTo(88L);
        assertThat(result.migrationError()).isNull();
    }

    @Test
    void 迁移未受理时记录失败但重置本身完成() throws Exception {
        when(migrations.migrate(1, "admin")).thenReturn(new VersionRebuildCoordinator.StartResult(false, null, false, "BUSY"));
        var result = service.reset(IndexForceResetService.CONFIRMATION, "admin");
        assertThat(result.migrationError()).isEqualTo("BUSY");
        verify(jdbc).update(contains("'FORCE_RESET'"), isNull(), eq("admin"), anyString(), anyString(), eq("FAILURE"), contains("BUSY"));
    }
}
