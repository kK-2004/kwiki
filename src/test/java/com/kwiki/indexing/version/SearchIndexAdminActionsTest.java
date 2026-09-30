package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexAdminActionsTest {

    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3);
    private final String hash = new ChunkMappingBuilder().mappingHash(1024, 3);

    private SearchIndexAdminQueryService.Actions actions(SearchIndexVersion version, boolean activeRun,
                                                         boolean scoped) {
        return SearchIndexAdminQueryService.actionsFor(version,
                version.toSnapshot(activeRun), activeRun, scoped, false, true);
    }

    @Test
    void 待迁移且写入关闭_只能开启写入_迁移按钮提示先开启写入() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        var result = actions(v2, false, false);
        assertThat(result.allowed()).containsEntry("writeToggle", true)
                .containsEntry("migrate", false).containsEntry("edit", true);
        assertThat(result.migrateBlockedReason()).isEqualTo("请先开启写入");
    }

    @Test
    void 待迁移且写入开启_可以开始存量迁移() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        v2.startWriteSession(42L);
        var result = actions(v2, false, false);
        assertThat(result.allowed()).containsEntry("migrate", true)
                .containsEntry("edit", false).containsEntry("select", false);
        assertThat(result.migrateBlockedReason()).isNull();
    }

    @Test
    void 已发布版本不能关闭写入也不能迁移() {
        SearchIndexVersion v1 = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        var result = actions(v1, false, false);
        assertThat(result.allowed()).containsEntry("writeToggle", false)
                .containsEntry("migrate", false).containsEntry("select", false);
    }

    @Test
    void 迁移中不能切换写入() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        v2.startWriteSession(42L);
        assertThat(actions(v2, true, false).allowed()).containsEntry("writeToggle", false)
                .containsEntry("migrate", false);
    }

    @Test
    void 灰度版本在全局页没有生命周期操作() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        var result = actions(v2, false, true);
        assertThat(result.allowed()).containsEntry("writeToggle", false)
                .containsEntry("migrate", false).containsEntry("edit", false);
        assertThat(result.migrateBlockedReason()).isNull();
    }
}
