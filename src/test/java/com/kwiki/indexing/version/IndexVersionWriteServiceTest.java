package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndexVersionWriteServiceTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3);
    private final String hash = new ChunkMappingBuilder().mappingHash(1024, 3);
    private IndexVersionWriteService service;

    static IndexingProperties properties(boolean mutations) {
        return new IndexingProperties(List.of(), Map.of(),
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(mutations));
    }

    @BeforeEach
    void setUp() {
        when(locks.acquire(anyString(), any(Duration.class))).thenReturn(() -> { });
        service = new IndexVersionWriteService(versions, runs, indexes, jdbc, tx, properties(true), locks);
    }

    private SearchIndexVersion offline() {
        SearchIndexVersion version = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        when(versions.findByVersionNumberForUpdate(2)).thenReturn(Optional.of(version));
        return version;
    }

    @Test
    void 开启写入_先清空物理索引再记录双写起点() throws Exception {
        SearchIndexVersion version = offline();
        when(jdbc.queryForObject(contains("indexing_job_target"), eq(Long.class), anyInt())).thenReturn(0L);
        when(jdbc.queryForObject(contains("search_index_change_event"), eq(Long.class))).thenReturn(42L);

        SearchIndexVersion enabled = service.enable(2);

        InOrder order = inOrder(indexes, jdbc);
        order.verify(indexes).recreateOfflineVersion("kwiki-chunks-v2", 1024, 3);
        order.verify(jdbc).queryForObject(contains("search_index_change_event"), eq(Long.class));
        assertThat(enabled.isWriteEnabled()).isTrue();
        assertThat(enabled.getWriteEnabledEventId()).isEqualTo(42L);
        assertThat(enabled.getBuildState()).isEqualTo(IndexBuildState.NEW.name());
        assertThat(enabled.getCatchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND.name());
    }

    @Test
    void 仍有未完成写入任务时拒绝开启且不清空索引() throws Exception {
        offline();
        when(jdbc.queryForObject(contains("indexing_job_target"), eq(Long.class), anyInt())).thenReturn(3L);
        assertThatThrownBy(() -> service.enable(2)).isInstanceOf(IllegalStateException.class);
        verify(indexes, never()).recreateOfflineVersion(anyString(), anyInt(), anyInt());
    }

    @Test
    void 迁移运行中拒绝开启() {
        offline();
        when(runs.existsByVersionNumberAndStateIn(eq(2), anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> service.enable(2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 关闭写入_清空双写起点并标记落后() {
        SearchIndexVersion version = offline();
        version.startWriteSession(42L);
        SearchIndexVersion disabled = service.disable(2);
        assertThat(disabled.isWriteEnabled()).isFalse();
        assertThat(disabled.getWriteEnabledEventId()).isNull();
        assertThat(disabled.getCatchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND.name());
    }

    @Test
    void 已发布版本拒绝关闭写入() {
        SearchIndexVersion published = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        when(versions.findByVersionNumberForUpdate(1)).thenReturn(Optional.of(published));
        assertThatThrownBy(() -> service.disable(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 管理写操作关闭时拒绝() {
        service = new IndexVersionWriteService(versions, runs, indexes, jdbc, tx, properties(false), locks);
        assertThatThrownBy(() -> service.enable(2))
                .hasMessage(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
    }

    @Test
    void 版本锁被占用时拒绝开启且不清空索引() throws Exception {
        offline();
        when(locks.acquire(anyString(), any(Duration.class))).thenReturn(null);
        assertThatThrownBy(() -> service.enable(2)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("busy");
        verify(indexes, never()).recreateOfflineVersion(anyString(), anyInt(), anyInt());
        verify(indexes, never()).recreateOfflineVersion(anyString(), anyInt());
    }

    @Test
    void 开启写入在版本锁内完成清空() throws Exception {
        offline();
        when(jdbc.queryForObject(contains("indexing_job_target"), eq(Long.class), anyInt())).thenReturn(0L);
        when(jdbc.queryForObject(contains("search_index_change_event"), eq(Long.class))).thenReturn(42L);
        AutoCloseable handle = mock(AutoCloseable.class);
        when(locks.acquire(eq("index-rebuild:v2"), any(Duration.class))).thenReturn(handle);

        service.enable(2);

        InOrder order = inOrder(locks, indexes, handle);
        order.verify(locks).acquire(eq("index-rebuild:v2"), any(Duration.class));
        order.verify(indexes).recreateOfflineVersion("kwiki-chunks-v2", 1024, 3);
        order.verify(handle).close();
    }
}
