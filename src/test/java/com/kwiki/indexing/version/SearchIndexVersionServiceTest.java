package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 版本号分配与编辑契约：单调、不复用（tombstone 保留）、并发冲突
 * 重试、修订原子递增、离线限制。
 */
class SearchIndexVersionServiceTest {

    @Test
    void mappingHashOrmTypeMatchesTheAppliedV21Schema() throws Exception {
        jakarta.persistence.Column column = SearchIndexVersion.class
                .getDeclaredField("mappingHash")
                .getAnnotation(jakarta.persistence.Column.class);

        assertThat(column).isNotNull();
        assertThat(column.length()).isEqualTo(64);
        assertThat(column.columnDefinition()).isEqualTo("CHAR(64)");
    }

    private final SearchIndexVersionRepository repository =
            Mockito.mock(SearchIndexVersionRepository.class);
    private final Map<Integer, SearchIndexVersion> rows = new HashMap<>();
    private SearchIndexVersionService service;
    private int saveFailuresLeft;

    @BeforeEach
    void setUp() {
        service = new SearchIndexVersionService(com.kwiki.testutil.StandardTestProperties.providerOf(repository), new ChunkMappingBuilder());
        when(repository.findMaxVersionNumber()).thenAnswer(inv ->
                rows.keySet().stream().max(Integer::compare).orElse(null));
        when(repository.findByVersionNumber(any(Integer.class))).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.getArgument(0, Integer.class))));
        when(repository.save(any(SearchIndexVersion.class))).thenAnswer(inv -> {
            SearchIndexVersion entity = inv.getArgument(0);
            if (saveFailuresLeft-- > 0) {
                // 模拟并发：另一管理员先把该号插入了。
                rows.put(entity.getVersionNumber(), entity);
                throw new DataIntegrityViolationException("Duplicate entry");
            }
            rows.put(entity.getVersionNumber(), entity);
            return entity;
        });
    }

    private static EditableIndexConfig config(int dimensions) {
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default",
                "text-embedding-v4", dimensions, 1);
    }

    @Test
    void firstVersionIsOneAndCarriesBuiltNothingYet() {
        SearchIndexVersion created = service.createNext(config(1024));

        assertThat(created.getVersionNumber()).isEqualTo(1);
        assertThat(created.getPhysicalName()).isEqualTo("kwiki-chunks-v1");
        assertThat(created.getConfigRevision()).isEqualTo(1);
        assertThat(created.getBuiltConfigRevision()).isNull();
        assertThat(created.getMappingHash()).hasSize(64);
    }

    @Test
    void versionNumbersStayMonotonicAcrossTombstones() {
        rows.put(2, tombstoned(2));

        SearchIndexVersion created = service.createNext(config(1024));

        assertThat(created.getVersionNumber()).isEqualTo(3);
        assertThat(created.getPhysicalName()).isEqualTo("kwiki-chunks-v3");
    }

    @Test
    void allocationContentionResolvesByRereadingTheMaximum() {
        saveFailuresLeft = 1;
        rows.put(1, SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config(1024), "hash"));

        SearchIndexVersion created = service.createNext(config(2048));

        assertThat(created.getVersionNumber()).isEqualTo(3); // 争抢中 2 已被占用
    }

    @Test
    void exhaustedAllocationRetriesFailExplicitly() {
        saveFailuresLeft = 99;

        assertThatThrownBy(() -> service.createNext(config(1024)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contention");
    }

    @Test
    void editingAnOfflineVersionIncrementsRevisionAndRefreshesHash() {
        rows.put(2, builtOffline(2, "hash-1024"));

        SearchIndexVersion edited = service.edit(2, config(2048));

        assertThat(edited.getVersionNumber()).isEqualTo(2);
        assertThat(edited.getConfigRevision()).isEqualTo(2);
        assertThat(edited.getBuiltConfigRevision()).isEqualTo(1L);
        assertThat(edited.getMappingHash()).isNotEqualTo("hash-1024").hasSize(64);
        assertThat(new IndexVersionStatusPolicyTestBridge().dirty(edited)).isTrue();
    }

    @Test
    void editingASelectedVersionIsRejected() {
        SearchIndexVersion selected = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", config(1024), "hash");
        rows.put(1, selected);

        assertThatThrownBy(() -> service.edit(1, config(2048)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create the next auto-numbered version");
    }

    @Test
    void editingAnUnknownVersionIsRejected() {
        assertThatThrownBy(() -> service.edit(9, config(1024)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown index version: 9");
    }

    private static SearchIndexVersion tombstoned(int number) {
        SearchIndexVersion version = SearchIndexVersion.bootstrapped(
                number, "kwiki-chunks-v" + number, config(1024), "hash");
        version.tombstone();
        return version;
    }

    /** 已构建但离线（非别名目标、未启用写入、空闲）的版本。 */
    private static SearchIndexVersion builtOffline(int number, String mappingHash) {
        SearchIndexVersion version = SearchIndexVersion.bootstrapped(
                number, "kwiki-chunks-v" + number, config(1024), mappingHash);
        IndexVersionSnapshot snapshot = IndexVersionStatusPolicy.disableWrites(
                IndexVersionStatusPolicy.unpublish(version.toSnapshot(false, false)));
        version.applySnapshot(snapshot);
        return version;
    }

    /** 测试桥接：dirty 派生事实。 */
    private static final class IndexVersionStatusPolicyTestBridge {
        boolean dirty(SearchIndexVersion version) {
            return version.toSnapshot(false, false).dirty();
        }
    }
}
