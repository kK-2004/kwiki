package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexAdminActionsTest {

    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, "kwiki-schema-1");
    private final String hash = new ChunkMappingBuilder(com.kwiki.indexing.schema.SchemaTestFixtures.schemas()).mappingHash(1024, "kwiki-schema-1");

    private SearchIndexAdminQueryService.Actions actions(SearchIndexVersion version, boolean activeRun,
                                                         boolean scoped) {
        return SearchIndexAdminQueryService.actionsFor(version,
                version.toSnapshot(activeRun), activeRun, scoped, null, true);
    }

    @Test
    void 引用未注册插件的版本_只能关闭写入与清理() {
        SearchIndexVersion v4 = new SearchIndexVersion(4, "kwiki-chunks-v4", config, hash);
        v4.startWriteSession(42L);
        var writing = SearchIndexAdminQueryService.actionsFor(v4, v4.toSnapshot(false), false, false,
                null, "字段格式 legacy-schema-2 未注册", true);
        assertThat(writing.allowed()).containsEntry("writeToggle", true).containsEntry("edit", false)
                .containsEntry("migrate", false).containsEntry("validate", false)
                .containsEntry("select", false).containsEntry("delete", false);
        assertThat(writing.migrateBlockedReason()).isNull();

        v4.endWriteSession();
        var offline = SearchIndexAdminQueryService.actionsFor(v4, v4.toSnapshot(false), false, false,
                null, "字段格式 legacy-schema-2 未注册", true);
        assertThat(offline.allowed()).containsEntry("writeToggle", false).containsEntry("delete", true);
    }

    @Test
    void 灰度版本在全局页不给出迁移提示() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        var result = SearchIndexAdminQueryService.actionsFor(v2, v2.toSnapshot(false), false, true,
                "解析器 kwiki-parse-2 不可用：缺少配置", true);
        assertThat(result.migrateBlockedReason()).isNull();
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

    @Test void 插件不可用时迁移和选择均被拒绝且原因可见() {
        var version = SearchIndexVersion.bootstrapped(2, "kwiki-chunks-v2", config, hash);
        var actions = SearchIndexAdminQueryService.actionsFor(version, version.toSnapshot(false),
                false, false, "解析器 third-parser 不可用：测试不可用", false);
        assertThat(actions.allowed()).containsEntry("migrate", false).containsEntry("select", false);
        assertThat(actions.migrateBlockedReason()).contains("third-parser").contains("测试不可用");
    }
}
