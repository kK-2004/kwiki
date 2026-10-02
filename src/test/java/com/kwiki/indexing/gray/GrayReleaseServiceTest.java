package com.kwiki.indexing.gray;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.version.*;
import com.kwiki.wiki.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class GrayReleaseServiceTest {

    private final InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
    private final SearchIndexAdminService admin = mock(SearchIndexAdminService.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final IndexVersionKbScope scope = mock(IndexVersionKbScope.class);
    private final IndexVersionWriteService writes = mock(IndexVersionWriteService.class);
    private final IndexMigrationService migrations = mock(IndexMigrationService.class);
    private final SearchIndexValidationService validations = mock(SearchIndexValidationService.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final ParserCatalog parsers = mock(ParserCatalog.class);
    private GrayReleaseService service;

    private final EditableIndexConfig globalConfig =
            new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, 3);

    @BeforeEach
    void setUp() {
        service = new GrayReleaseService(store, admin, versions, scope, writes, migrations,
                validations, runs, parsers);
        String hash = new ChunkMappingBuilder().mappingHash(1024, 3);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", globalConfig, hash)));
        when(admin.createVersion(any())).thenAnswer(invocation -> SearchIndexVersion.bootstrapped(
                2, "kwiki-chunks-v2", invocation.getArgument(0), hash));
        // 解析器目录按结构清单生成灰度配置；此处模拟清单结构版本与全局一致的情形
        when(parsers.configFor(any(), any())).thenAnswer(invocation -> {
            EditableIndexConfig base = invocation.getArgument(1);
            return Optional.of(new EditableIndexConfig(invocation.getArgument(0), base.chunkerVersion(),
                    base.embeddingProvider(), base.embeddingModel(), base.embeddingDimensions(),
                    base.mappingSchemaVersion()));
        });
    }

    @Test
    void 创建灰度_使用解析器目录按清单给出的配置() {
        EditableIndexConfig fromManifest =
                new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 2);
        org.mockito.Mockito.doReturn(Optional.of(fromManifest))
                .when(parsers).configFor(org.mockito.ArgumentMatchers.eq("kwiki-parse-2"), any());
        service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        verify(admin).createVersion(fromManifest);
    }

    @Test
    void 没有兼容的结构清单时拒绝创建且不建版本() {
        org.mockito.Mockito.doReturn(Optional.empty())
                .when(parsers).configFor(org.mockito.ArgumentMatchers.eq("kwiki-parse-2"), any());
        assertThatThrownBy(() -> service.create(null, "kwiki-parse-2", List.of(7L), "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("kwiki.indexing.manifests");
        verify(admin, never()).createVersion(any());
    }

    @Test
    void 创建灰度_以全局配置为基础替换解析器并登记知识库范围() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L, 9L), "admin");

        verify(parsers).requireAvailable("kwiki-parse-2");
        verify(admin).createVersion(new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3));
        verify(scope).register(2, List.of(7L, 9L));
        assertThat(created.status()).isEqualTo(GrayReleaseStatus.CREATED);
        assertThat(created.indexVersionNumber()).isEqualTo(2);
        assertThat(created.name()).isEqualTo("pdfbox-v2 灰度 #1");
        assertThat(created.kbIds()).containsExactly(7L, 9L);
    }

    @Test
    void 知识库已在其他未结束灰度中时拒绝创建且不建版本() {
        store.insert("old", "kwiki-parse-2", 5, "admin");
        store.insertKbs(1, List.of(9L));
        assertThatThrownBy(() -> service.create(null, "kwiki-parse-2", List.of(7L, 9L), "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("9");
        verify(admin, never()).createVersion(any());
    }

    @Test
    void 空知识库列表拒绝创建() {
        assertThatThrownBy(() -> service.create("x", "kwiki-parse-2", List.of(), "admin"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 开始同步_先开启双写再发起存量迁移() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        grayVersion(false, null);
        SearchIndexVersion enabled = mock(SearchIndexVersion.class);
        when(enabled.isWriteEnabled()).thenReturn(true);
        when(enabled.getWriteEnabledEventId()).thenReturn(42L);
        when(writes.enable(2)).thenReturn(enabled);
        when(migrations.migrate(2, "admin")).thenReturn(acceptedMigration());

        GrayRelease syncing = service.sync(created.id(), "admin");

        org.mockito.InOrder order = inOrder(writes, migrations);
        order.verify(writes).enable(2);
        order.verify(migrations).migrate(2, "admin");
        assertThat(syncing.status()).isEqualTo(GrayReleaseStatus.SYNCING);
    }

    @Test
    void 重试同步_写入仍开启时保留双写只重新迁移() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, "同步失败：x");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.FAILED);
        when(migrations.migrate(2, "admin")).thenReturn(acceptedMigration());

        GrayRelease syncing = service.sync(created.id(), "admin");

        verify(writes, never()).enable(anyInt());
        verify(migrations).migrate(2, "admin");
        assertThat(syncing.lastError()).isNull();
    }

    @Test
    void 推进_双写未开启时拒绝迁移并记录原因() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(false, null);

        GrayRelease advanced = service.advance(created.id());

        verify(migrations, never()).migrate(anyInt(), any());
        assertThat(advanced.lastError()).contains("双写未开启");
    }

    @Test
    void 推进_本会话尚无迁移时发起迁移() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(true, 42L);
        migrationRun(7L, RebuildRunState.COMPLETED); // 上一个写入会话的迁移
        when(migrations.migrate(eq(2), any())).thenReturn(acceptedMigration());

        service.advance(created.id());

        verify(migrations).migrate(eq(2), any());
    }

    @Test
    void 推进_迁移准入繁忙时不记失败_下一轮自动重试() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(true, 42L);
        when(migrations.migrate(eq(2), any())).thenReturn(
                new VersionRebuildCoordinator.StartResult(false, null, false, "BUSY"));

        GrayRelease first = service.advance(created.id());
        assertThat(first.lastError()).isNull();

        when(migrations.migrate(eq(2), any())).thenReturn(acceptedMigration());
        service.advance(created.id());
        verify(migrations, times(2)).migrate(eq(2), any());
    }

    @Test
    void 推进_本会话迁移完成后校验_通过即已同步() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.COMPLETED);
        SearchIndexValidationReport report = mock(SearchIndexValidationReport.class);
        when(report.getStatus()).thenReturn("PASS");
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        when(validations.validate(2)).thenReturn(report);

        GrayRelease advanced = service.advance(created.id());

        verify(migrations, never()).migrate(anyInt(), any());
        assertThat(advanced.status()).isEqualTo(GrayReleaseStatus.SYNCED);
    }

    @Test
    void 推进_本会话迁移失败_记录原因等待重试() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null); // 无失败原因才会推进到迁移结果判断
        grayVersion(true, 42L);
        SearchIndexRebuildRun run = migrationRun(42L, RebuildRunState.FAILED);
        when(run.getErrorSummary()).thenReturn("boom");

        GrayRelease advanced = service.advance(created.id());

        assertThat(advanced.lastError()).contains("boom");
        verify(migrations, never()).migrate(anyInt(), any());
    }

    @Test
    void 推进_已记录失败原因时停在原地() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, "校验未通过：x");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.COMPLETED); // 即便本会话迁移已完成，也不得重跑校验

        GrayRelease advanced = service.advance(created.id());

        assertThat(advanced.status()).isEqualTo(GrayReleaseStatus.SYNCING);
        assertThat(advanced.lastError()).isEqualTo("校验未通过：x");
        verifyNoInteractions(migrations);
        verify(validations, never()).validate(anyInt());
    }

    @Test
    void 切换与切回_只允许在对应状态下进行() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class);

        store.setStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.of(mock(SearchIndexValidationReport.class)));
        assertThat(service.switchTo(created.id()).status()).isEqualTo(GrayReleaseStatus.SWITCHED);
        assertThat(service.switchBack(created.id()).status()).isEqualTo(GrayReleaseStatus.SYNCED);
        assertThatThrownBy(() -> service.switchBack(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void 切换前校验报告已失效则拒绝() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void 切换被拒时回到同步中且不停放() throws Exception {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("自动重新校验");
        GrayRelease after = service.find(created.id());
        assertThat(after.status()).isEqualTo(GrayReleaseStatus.SYNCING);
        assertThat(after.lastError()).isNull();
        Transactional tx = GrayReleaseService.class.getMethod("switchTo", long.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.noRollbackFor()).contains(ConflictException.class);
    }

    @Test
    void 用户操作时状态已被并发改变则拒绝() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenAnswer(invocation -> {
            store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
            return Optional.of(mock(SearchIndexValidationReport.class));
        });
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("状态已变化");
        assertThat(service.find(created.id()).status()).isEqualTo(GrayReleaseStatus.SYNCING);
    }

    @Test
    void 结束灰度_关闭写入() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.COMPLETED);

        service.end(created.id());

        verify(writes).disable(2);
    }

    @Test
    void 结束灰度_迁移进行中拒绝() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.RUNNING);
        assertThatThrownBy(() -> service.end(created.id())).isInstanceOf(ConflictException.class);
        verify(writes, never()).disable(anyInt());
    }

    @Test
    void 结束灰度_置已结束并释放知识库_再次结束拒绝() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.COMPLETED); // 避免活动迁移 run 拒绝结束

        GrayRelease ended = service.end(created.id());

        assertThat(ended.status()).isEqualTo(GrayReleaseStatus.ENDED);
        assertThat(ended.endedAt()).isNotNull();
        assertThat(store.activeReleaseByKb(List.of(7L))).isEmpty();
        // 释放占用后，同一知识库可再次创建灰度
        assertThat(service.create(null, "kwiki-parse-2", List.of(7L), "admin").status())
                .isEqualTo(GrayReleaseStatus.CREATED);
        assertThatThrownBy(() -> service.end(created.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已结束");
    }

    @Test
    void 名称超过120个字符拒绝创建() {
        assertThatThrownBy(() -> service.create("x".repeat(121), "kwiki-parse-2", List.of(7L), "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("120");
        verify(admin, never()).createVersion(any());
    }

    @Test
    void 切换前重新确认解析器可用_不可用时拒绝且状态不变() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.of(mock(SearchIndexValidationReport.class)));
        doThrow(new ConflictException("解析器 pdfbox-v2 暂不可用")).when(parsers).requireAvailable("kwiki-parse-2");

        assertThatThrownBy(() -> service.switchTo(created.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("暂不可用");
        assertThat(service.find(created.id()).status()).isEqualTo(GrayReleaseStatus.SYNCED);
    }

    @Test
    void 管理写操作关闭时拒绝创建同步切换切回与结束() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        GrayReleaseService readOnly = new GrayReleaseService(store, admin, versions, scope, writes, migrations,
                validations, runs, parsers,
                new com.kwiki.indexing.config.IndexingProperties(null, null, null, null, null,
                        new com.kwiki.indexing.config.IndexingProperties.Management(false)));
        String message = "索引管理写操作已被关闭";

        assertThatThrownBy(() -> readOnly.create(null, "kwiki-parse-2", List.of(8L), "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining(message);
        assertThatThrownBy(() -> readOnly.sync(created.id(), "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining(message);
        store.setStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        assertThatThrownBy(() -> readOnly.switchTo(created.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining(message);
        store.setStatus(created.id(), GrayReleaseStatus.SWITCHED, null);
        assertThatThrownBy(() -> readOnly.switchBack(created.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining(message);
        assertThatThrownBy(() -> readOnly.end(created.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining(message);
        assertThat(store.find(created.id()).orElseThrow().status()).isEqualTo(GrayReleaseStatus.SWITCHED);
        verify(migrations, never()).migrate(anyInt(), any());
        verifyNoInteractions(writes);
    }

    private SearchIndexVersion grayVersion(boolean writeEnabled, Long eventId) {
        SearchIndexVersion version = mock(SearchIndexVersion.class);
        when(version.getVersionNumber()).thenReturn(2);
        when(version.isWriteEnabled()).thenReturn(writeEnabled);
        when(version.getWriteEnabledEventId()).thenReturn(eventId);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        return version;
    }

    private SearchIndexRebuildRun migrationRun(long startEventId, RebuildRunState state) {
        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(run.kind()).thenReturn(RebuildRunKind.MIGRATION);
        when(run.getBuildStartEventId()).thenReturn(startEventId);
        when(run.state()).thenReturn(state);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        return run;
    }

    private static VersionRebuildCoordinator.StartResult acceptedMigration() {
        return new VersionRebuildCoordinator.StartResult(true, 9L, false, "ACCEPTED");
    }
}
